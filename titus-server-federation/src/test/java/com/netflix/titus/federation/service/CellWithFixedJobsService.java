package com.netflix.titus.federation.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.protobuf.Empty;
import com.netflix.titus.api.model.Pagination;
import com.netflix.titus.api.model.PaginationUtil;
import com.netflix.titus.common.util.ProtobufCopy;
import com.netflix.titus.common.util.tuple.Pair;
import com.netflix.titus.grpc.protogen.Job;
import com.netflix.titus.grpc.protogen.JobChangeNotification;
import com.netflix.titus.grpc.protogen.JobChangeNotification.SnapshotEnd;
import com.netflix.titus.grpc.protogen.JobId;
import com.netflix.titus.grpc.protogen.JobManagementServiceGrpc;
import com.netflix.titus.grpc.protogen.JobQuery;
import com.netflix.titus.grpc.protogen.JobQueryResult;
import com.netflix.titus.runtime.endpoint.common.grpc.GrpcUtil;
import com.netflix.titus.runtime.jobmanager.JobManagerCursors;
import io.grpc.stub.StreamObserver;
import rx.Observable;
import rx.Subscription;

import static com.netflix.titus.runtime.endpoint.common.grpc.CommonGrpcModelConverters.toGrpcPagination;
import static com.netflix.titus.runtime.endpoint.common.grpc.CommonGrpcModelConverters.toPage;
import static io.grpc.Status.NOT_FOUND;

class CellWithFixedJobsService extends JobManagementServiceGrpc.JobManagementServiceImplBase {
    private final Map<String, Job> jobsIndex;
    private final Observable<JobChangeNotification> updates;

    CellWithFixedJobsService(List<Job> snapshot, Observable<JobChangeNotification> updates) {
        this.jobsIndex = snapshot.stream().collect(Collectors.toMap(Job::getId, Function.identity()));
        this.updates = updates;
    }

    private List<Job> getJobsList() {
        return new ArrayList<>(jobsIndex.values());
    }

    @Override
    public void findJob(JobId request, StreamObserver<Job> responseObserver) {
        Job job = jobsIndex.get(request.getId());
        if (job == null) {
            responseObserver.onError(NOT_FOUND.asRuntimeException());
            return;
        }
        responseObserver.onNext(job);
        responseObserver.onCompleted();
    }

    @Override
    public void findJobs(JobQuery request, StreamObserver<JobQueryResult> responseObserver) {
        Pair<List<Job>, Pagination> page = PaginationUtil.takePageWithCursor(
                toPage(request.getPage()),
                getJobsList(),
                JobManagerCursors.jobCursorOrderComparator(),
                JobManagerCursors::jobIndexOf,
                JobManagerCursors::newCursorFrom
        );
        Set<String> fieldsFilter = new HashSet<>(request.getFieldsList());
        if (!fieldsFilter.isEmpty()) {
            fieldsFilter.add("id");
            page = page.mapLeft(jobs -> jobs.stream()
                    .map(job -> ProtobufCopy.copy(job, fieldsFilter))
                    .collect(Collectors.toList()));
        }
        JobQueryResult result = JobQueryResult.newBuilder()
                .addAllItems(page.getLeft())
                .setPagination(toGrpcPagination(page.getRight()))
                .build();
        responseObserver.onNext(result);
        responseObserver.onCompleted();
    }

    @Override
    public void killJob(JobId request, StreamObserver<Empty> responseObserver) {
        if (!jobsIndex.containsKey(request.getId())) {
            responseObserver.onError(NOT_FOUND.asRuntimeException());
            return;
        }
        jobsIndex.remove(request.getId());
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void observeJobs(Empty request, StreamObserver<JobChangeNotification> responseObserver) {
        for (Job job : jobsIndex.values()) {
            JobChangeNotification.JobUpdate update = JobChangeNotification.JobUpdate.newBuilder().setJob(job).build();
            JobChangeNotification notification = JobChangeNotification.newBuilder().setJobUpdate(update).build();
            responseObserver.onNext(notification);
        }
        SnapshotEnd snapshotEnd = SnapshotEnd.newBuilder().build();
        JobChangeNotification marker = JobChangeNotification.newBuilder().setSnapshotEnd(snapshotEnd).build();
        responseObserver.onNext(marker);

        final Subscription subscription = updates.subscribe(
                responseObserver::onNext,
                responseObserver::onError,
                responseObserver::onCompleted
        );
        GrpcUtil.attachCancellingCallback(responseObserver, subscription);
    }

    Map<String, Job> currentJobs() {
        return Collections.unmodifiableMap(jobsIndex);
    }
}
