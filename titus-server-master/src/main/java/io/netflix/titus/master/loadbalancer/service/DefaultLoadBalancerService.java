/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.netflix.titus.master.loadbalancer.service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.annotations.VisibleForTesting;
import io.netflix.titus.api.connector.cloud.LoadBalancerClient;
import io.netflix.titus.api.jobmanager.model.job.Task;
import io.netflix.titus.api.jobmanager.model.job.TaskState;
import io.netflix.titus.api.jobmanager.service.V3JobOperations;
import io.netflix.titus.api.loadbalancer.model.JobLoadBalancer;
import io.netflix.titus.api.loadbalancer.model.LoadBalancerTarget;
import io.netflix.titus.api.loadbalancer.service.LoadBalancerService;
import io.netflix.titus.api.loadbalancer.store.LoadBalancerStore;
import io.netflix.titus.common.util.CollectionsExt;
import io.netflix.titus.common.util.guice.annotation.Activator;
import io.netflix.titus.common.util.guice.annotation.Deactivator;
import io.netflix.titus.common.util.rx.ObservableExt;
import io.netflix.titus.runtime.endpoint.v3.grpc.TaskAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Completable;
import rx.Observable;
import rx.Subscription;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

@Singleton
public class DefaultLoadBalancerService implements LoadBalancerService {
    private static Logger logger = LoggerFactory.getLogger(DefaultLoadBalancerService.class);

    private final LoadBalancerConfiguration configuration;
    private final LoadBalancerClient loadBalancerClient;
    private final LoadBalancerStore loadBalancerStore;
    private final V3JobOperations v3JobOperations;

    private Subject<JobLoadBalancer, JobLoadBalancer> pendingAssociations;
    private Subject<JobLoadBalancer, JobLoadBalancer> pendingDissociations;
    private Subscription loadBalancerBatches;

    @Inject
    public DefaultLoadBalancerService(LoadBalancerConfiguration configuration,
                                      LoadBalancerClient loadBalancerClient,
                                      LoadBalancerStore loadBalancerStore,
                                      V3JobOperations v3JobOperations) {
        this.configuration = configuration;
        this.loadBalancerClient = loadBalancerClient;
        this.loadBalancerStore = loadBalancerStore;
        this.v3JobOperations = v3JobOperations;
    }

    @Override
    public Observable<String> getJobLoadBalancers(String jobId) {
        return loadBalancerStore.retrieveLoadBalancersForJob(jobId);
    }

    @Override
    public Completable addLoadBalancer(String jobId, String loadBalancerId) {
        final JobLoadBalancer jobLoadBalancer = new JobLoadBalancer(jobId, loadBalancerId);
        return loadBalancerStore.addLoadBalancer(jobLoadBalancer)
                .andThen(Completable.fromAction(
                        () -> pendingAssociations.onNext(jobLoadBalancer)
                ));
    }

    @Override
    public Completable removeLoadBalancer(String jobId, String loadBalancerId) {
        final JobLoadBalancer jobLoadBalancer = new JobLoadBalancer(jobId, loadBalancerId);
        return loadBalancerStore.removeLoadBalancer(jobLoadBalancer)
                .andThen(Completable.fromAction(
                        () -> pendingDissociations.onNext(jobLoadBalancer)
                ));
    }

    @VisibleForTesting
    Observable<Batch> buildStream() {
        pendingAssociations = PublishSubject.<JobLoadBalancer>create().toSerialized();
        pendingDissociations = PublishSubject.<JobLoadBalancer>create().toSerialized();

        final Observable<LoadBalancerTarget> toRegister = targetsToRegister(pendingAssociations);
        final Observable<LoadBalancerTarget> toDeregister = targetsToDeregister(pendingDissociations);
        return loadBalancerBatchCalls(toRegister, toDeregister);
    }

    @Activator
    public void activate() {
        loadBalancerBatches = buildStream()
                .subscribeOn(Schedulers.computation())
                .subscribe(
                        batch -> logger.info("Load balancer batch completed. Registered {}, deregistered {}",
                                batch.getStateRegister().size(), batch.getStateDeregister().size()),
                        e -> logger.error("Error while processing load balancer batch", e),
                        () -> logger.info("Load balancer batch stream closed")
                );

        // TODO(fabio): reconciliation
        // TODO(fabio): watch task and job update streams
        // TODO(fabio): garbage collect removed jobs and loadbalancers
        // TODO(fabio): garbage collect removed tasks
        // TODO(fabio): integrate with the V2 engine
    }

    @Deactivator
    public void deactivate() {
        ObservableExt.safeUnsubscribe(loadBalancerBatches);

        this.pendingAssociations.onCompleted();
        this.pendingDissociations.onCompleted();
    }

    @VisibleForTesting
    Observable<Batch> loadBalancerBatchCalls(Observable<LoadBalancerTarget> targetsToRegister, Observable<LoadBalancerTarget> targetsToDeregister) {
        return Observable.merge(targetsToRegister, targetsToDeregister)
                .doOnNext(e -> logger.debug("Buffering load balancer target {}", e))
                .buffer(configuration.getBatch().getTimeoutMs(), TimeUnit.MILLISECONDS,
                        configuration.getBatch().getSize())
                .doOnNext(e -> logger.debug("Processing batch operation of size {}", e.size()))
                .map(CollectionsExt::distinctKeepLast)
                .observeOn(Schedulers.io()) // blocking CloudClient calls go into the IO scheduler
                .flatMap(batch -> {
                    final Batch grouped = new Batch(batch);
                    final List<LoadBalancerTarget> registerList = grouped.getStateRegister();
                    final List<LoadBalancerTarget> deregisterList = grouped.getStateDeregister();
                    final Completable merged = Completable.mergeDelayError(
                            loadBalancerClient.registerAll(registerList)
                                    .observeOn(Schedulers.computation())
                                    .andThen(loadBalancerStore.updateTargets(registerList)),
                            loadBalancerClient.deregisterAll(deregisterList)
                                    .observeOn(Schedulers.computation())
                                    .andThen(loadBalancerStore.updateTargets(deregisterList))
                    );
                    return merged.andThen(Observable.just(grouped))
                            .doOnError(e -> logger.error("Error processing batch " + batch, e))
                            .onErrorResumeNext(Observable.empty());
                })
                .doOnNext(e -> logger.info("Processed load balancer batch: registered {}, deregistered",
                        e.getStateRegister().size(), e.getStateDeregister().size()))
                .observeOn(Schedulers.computation())
                .doOnError(e -> logger.error("Error processing a batch", e))
                .onErrorResumeNext(Observable.empty());
    }

    @VisibleForTesting
    Observable<LoadBalancerTarget> targetsToDeregister(Observable<JobLoadBalancer> pendingDissociations) {
        return pendingDissociations.flatMap(
                jobLoadBalancer -> loadBalancerStore.retrieveTargets(jobLoadBalancer).map(
                        target -> new LoadBalancerTarget(jobLoadBalancer,
                                target.getTaskId(),
                                target.getIpAddress(),
                                LoadBalancerTarget.State.Deregistered
                        )));
    }

    @VisibleForTesting
    Observable<LoadBalancerTarget> targetsToRegister(Observable<JobLoadBalancer> pendingAssociations) {
        return pendingAssociations
                .filter(jobLoadBalancer -> v3JobOperations.getJob(jobLoadBalancer.getJobId()).isPresent())
                .flatMap(jobLoadBalancer -> {
                    final LoadBalancerTarget.State desiredState = LoadBalancerTarget.State.Registered;
                    final String jobId = jobLoadBalancer.getJobId();
                    return Observable.merge(
                            v3JobOperations.getTasks(jobId).stream()
                                    .filter(task -> task.getStatus().getState() == TaskState.Started)
                                    .filter(DefaultLoadBalancerService::hasIp)
                                    .map(task -> {
                                        final String ipAddress = task.getTaskContext().get(TaskAttributes.TASK_ATTRIBUTES_CONTAINER_IP);
                                        return Observable.just(new LoadBalancerTarget(jobLoadBalancer, task.getId(), ipAddress, desiredState));
                                    }).collect(Collectors.toList()))
                            .doOnError(e -> logger.error("Error loading tasks for jobId " + jobId, e))
                            .onErrorResumeNext(Observable.empty()); // skip it
                });
    }

    private static boolean hasIp(Task task) {
        final boolean hasIp = task.getTaskContext().containsKey(TaskAttributes.TASK_ATTRIBUTES_CONTAINER_IP);
        if (!hasIp) {
            logger.warn("Task {} has state {} but no ipAddress associated", task.getId(), task.getStatus().getState());
        }
        return hasIp;
    }

    @VisibleForTesting
    static class Batch {
        private final Map<LoadBalancerTarget.State, List<LoadBalancerTarget>> groupedBy;

        public Batch(Collection<LoadBalancerTarget> batch) {
            groupedBy = batch.stream().collect(Collectors.groupingBy(LoadBalancerTarget::getState));
        }

        public List<LoadBalancerTarget> getStateRegister() {
            return groupedBy.getOrDefault(LoadBalancerTarget.State.Registered, Collections.emptyList());
        }

        public List<LoadBalancerTarget> getStateDeregister() {
            return groupedBy.getOrDefault(LoadBalancerTarget.State.Deregistered, Collections.emptyList());
        }

        @Override
        public String toString() {
            return "Batch{" +
                    "groupedBy=" + groupedBy +
                    '}';
        }
    }
}
