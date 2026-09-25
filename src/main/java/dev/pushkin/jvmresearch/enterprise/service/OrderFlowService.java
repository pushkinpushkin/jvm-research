package dev.pushkin.jvmresearch.enterprise.service;

import dev.pushkin.jvmresearch.enterprise.domain.*;
import dev.pushkin.jvmresearch.enterprise.external.*;
import dev.pushkin.jvmresearch.enterprise.kafka.*;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderFlowService {
    private final OrderRepository repository;
    private final ExternalApiClient externalApiClient;
    private final DtoMapperService mapper;
    private final EnterpriseEventPublisher publisher;
    private final TrafficProfile traffic;
    private final OrderLocks locks;
    private final ResearchMetrics metrics;

    public ProcessOrderResponse process(String id) {
        try {
            ProcessOrderResponse response = locks.withOrder(id, () -> processLocked(id));
            metrics.increment(
                    response.outcome().equals("processed")
                            ? "http_processed"
                            : "http_expected_fault");
            return response;
        } catch (RuntimeException ex) {
            metrics.increment("http_unexpected");
            throw ex;
        }
    }

    public void retry(String id) {
        locks.withOrder(
                id,
                () -> {
                    var order = repository.findById(id).orElseThrow();
                    if (order.getStatus() == OrderStatus.FAILED
                            && order.getFnsProcess().getAttempts() < 3) {
                        var result = processLocked(id);
                        if (result.outcome().equals("expected_fault"))
                            metrics.increment("scheduler_expected_fault");
                    }
                    return null;
                });
    }

    private ProcessOrderResponse processLocked(String id) {
        // Unseeded IDs are an invalid trace, never implicit extra data growth.
        var order =
                repository
                        .findById(id)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order was not seeded: " + id));
        order.getFnsProcess().setAttempts(order.getFnsProcess().getAttempts() + 1);
        String outcome = "processed";
        String message = "processed";
        try {
            var response =
                    Objects.requireNonNull(
                            externalApiClient.getFnsData(id), "Empty external response");
            var status = FnsProcessStatus.fromExternal(response.externalStatus());
            if (!status.isSuccessful())
                throw new IllegalStateException("Unexpected FNS business status: " + status);
            order.setPayload(mapper.mergeExternalFnsData(order, response));
            order.getFnsProcess().setStatus(status);
            order.getFnsProcess().setRegisteringFns(response.registeringFns());
            order.getFnsProcess().setRegisteringFnsName(response.registeringFnsName());
            order.getFnsProcess().setFailMessage(null);
            order.setStatus(OrderStatus.WAITING_EXTERNAL_STATUS);
            if (traffic.mongoConflict(id, BusinessEventSource.HTTP_PROCESS.value())) {
                metrics.increment("mongo_expected_fault");
                throw new SyntheticConflict();
            }
        } catch (ExpectedExternalFault | SyntheticConflict ex) {
            outcome = "expected_fault";
            message = ex.getMessage();
            order.setStatus(OrderStatus.FAILED);
            order.getFnsProcess().setFailMessage(message);
        }
        // Unexpected exceptions propagate as HTTP errors. Never save a stale failed document.
        order.setUpdatedAt(Instant.now());
        order.addHistory(
                order.getStatus().name(),
                BusinessEventSource.HTTP_PROCESS.value(),
                message,
                Instant.now());
        publisher.publishOrderStatusChanged(order, BusinessEventSource.HTTP_PROCESS);
        var saved = repository.save(order);
        metrics.increment("events_enqueued");
        return new ProcessOrderResponse(
                id, saved.getStatus().name(), message, saved.getVersion(), outcome);
    }

    private static class SyntheticConflict extends RuntimeException {
        SyntheticConflict() {
            super("Synthetic Mongo optimistic locking conflict");
        }
    }
}
