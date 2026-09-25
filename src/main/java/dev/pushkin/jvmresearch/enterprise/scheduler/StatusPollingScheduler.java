package dev.pushkin.jvmresearch.enterprise.scheduler;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.domain.*;
import dev.pushkin.jvmresearch.enterprise.external.*;
import dev.pushkin.jvmresearch.enterprise.kafka.*;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import dev.pushkin.jvmresearch.enterprise.service.OrderLocks;
import dev.pushkin.jvmresearch.enterprise.service.ResearchMetrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class StatusPollingScheduler {
    private final OrderRepository repository;
    private final ExternalApiClient external;
    private final EnterpriseEventPublisher publisher;
    private final SandboxProperties properties;
    private final OrderLocks locks;
    private final ResearchMetrics metrics;

    @Scheduled(fixedDelayString = "${sandbox.scheduler.status-polling-fixed-delay-ms}")
    public void pollExternalStatus() {
        if (!properties.scheduler().statusPollingEnabled()) return;
        metrics.increment("scheduler_runs");
        try {
            for (var candidate :
                    repository.findByStatusOrderByUpdatedAtAsc(
                            OrderStatus.WAITING_EXTERNAL_STATUS,
                            Pageable.ofSize(properties.scheduler().statusPollingBatchSize()))) {
                try {
                    pollOne(candidate.getId());
                } catch (RuntimeException ex) {
                    metrics.increment("scheduler_errors");
                    log.warn("Status polling failed", ex);
                }
            }
        } catch (RuntimeException ex) {
            metrics.increment("scheduler_errors");
            log.warn("Polling query failed", ex);
        }
    }

    private void pollOne(String id) {
        locks.withOrder(
                id,
                () -> {
                    var order = repository.findById(id).orElseThrow();
                    // Re-read under the shared lock: the query result may already be stale.
                    if (order.getStatus() != OrderStatus.WAITING_EXTERNAL_STATUS) return null;
                    try {
                        var response =
                                Objects.requireNonNull(
                                        external.getSignStatus(order.getExternalRequestId()));
                        var status = GoskeyProcessStatus.fromExternal(response.status());
                        order.getGoskeyProcess().setStatus(status);
                        if (status.isFinalSuccess()) order.setStatus(OrderStatus.COMPLETED);
                        else if (status.isFinalFailure()) order.setStatus(OrderStatus.FAILED);
                        else
                            throw new IllegalStateException("Unexpected external status " + status);
                    } catch (ExpectedExternalFault ex) {
                        metrics.increment("scheduler_expected_fault");
                        order.setStatus(OrderStatus.FAILED);
                        order.getGoskeyProcess().setFailMessage(ex.getMessage());
                    }
                    order.setUpdatedAt(Instant.now());
                    order.addHistory(
                            order.getStatus().name(),
                            BusinessEventSource.STATUS_POLLING_SCHEDULER.value(),
                            "External status polled",
                            Instant.now());
                    publisher.publishOrderStatusChanged(
                            order, BusinessEventSource.STATUS_POLLING_SCHEDULER);
                    repository.save(order);
                    metrics.increment("events_enqueued");
                    return null;
                });
    }
}
