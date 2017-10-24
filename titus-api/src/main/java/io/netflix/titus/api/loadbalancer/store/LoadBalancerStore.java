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

package io.netflix.titus.api.loadbalancer.store;

import java.util.Collection;

import io.netflix.titus.api.loadbalancer.model.JobLoadBalancer;
import io.netflix.titus.api.loadbalancer.model.LoadBalancerTarget;
import rx.Completable;
import rx.Observable;

public interface LoadBalancerStore {
    Observable<String> retrieveLoadBalancersForJob(String jobId);

    Completable addLoadBalancer(JobLoadBalancer jobLoadBalancer);

    Completable removeLoadBalancer(JobLoadBalancer jobLoadBalancer);

    Observable<LoadBalancerTarget> retrieveTargets(JobLoadBalancer jobLoadBalancer);

    /**
     * @param targets may be null or empty, in which case this is a noop
     */
    Completable updateTargets(Collection<LoadBalancerTarget> targets);
}
