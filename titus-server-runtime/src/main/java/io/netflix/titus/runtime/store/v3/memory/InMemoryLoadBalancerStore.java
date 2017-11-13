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

package io.netflix.titus.runtime.store.v3.memory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.netflix.titus.api.loadbalancer.model.JobLoadBalancer;
import io.netflix.titus.api.loadbalancer.model.LoadBalancerTarget;
import io.netflix.titus.api.loadbalancer.store.LoadBalancerStore;
import io.netflix.titus.common.util.CollectionsExt;
import io.netflix.titus.common.util.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Completable;
import rx.Observable;

/**
 * Operations are not being indexed yet for simplicity.
 */
public class InMemoryLoadBalancerStore implements LoadBalancerStore {
    private static Logger logger = LoggerFactory.getLogger(InMemoryLoadBalancerStore.class);

    private final ConcurrentMap<JobLoadBalancer, JobLoadBalancer.State> associations = new ConcurrentHashMap<>();
    private final ConcurrentMap<LoadBalancerTarget, LoadBalancerTarget.State> targets = new ConcurrentHashMap<>();

    @Override
    public Observable<Pair<String, JobLoadBalancer.State>> retrieveLoadBalancersForJob(String jobId) {
        return Observable.defer(() -> Observable.from(associations.entrySet())
                .filter(entry -> entry.getKey().getJobId().equals(jobId))
                .map(entry -> Pair.of(entry.getKey().getLoadBalancerId(), entry.getValue())));
    }

    @Override
    public Completable addOrUpdateLoadBalancer(JobLoadBalancer jobLoadBalancer, JobLoadBalancer.State state) {
        return Completable.fromAction(() -> associations.put(jobLoadBalancer, state));
    }

    @Override
    public Completable removeLoadBalancer(JobLoadBalancer jobLoadBalancer) {
        return Completable.fromAction(() -> associations.remove(jobLoadBalancer));
    }

    @Override
    public Observable<Pair<LoadBalancerTarget, LoadBalancerTarget.State>> retrieveTargets(JobLoadBalancer jobLoadBalancer) {
        return Observable.defer(() -> Observable.from(targets.entrySet())
                .filter(entry -> entry.getKey().getJobLoadBalancer().equals(jobLoadBalancer))
                .map(entry -> Pair.of(entry.getKey(), entry.getValue()))
        );
    }

    @Override
    public Completable updateTargets(Map<LoadBalancerTarget, LoadBalancerTarget.State> update) {
        if (CollectionsExt.isNullOrEmpty(update)) {
            return Completable.complete();
        }
        return Completable.fromAction(() -> update.forEach(targets::put));
    }

    @Override
    public Completable removeTargets(Collection<LoadBalancerTarget> remove) {
        if (CollectionsExt.isNullOrEmpty(remove)) {
            return Completable.complete();
        }
        return Completable.fromAction(() -> targets.keySet().removeAll(remove));
    }
}
