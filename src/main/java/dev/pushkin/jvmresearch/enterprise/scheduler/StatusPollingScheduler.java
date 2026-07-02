package dev.pushkin.jvmresearch.enterprise.scheduler;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.domain.GoskeyProcessStatus;
import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.domain.OrderStatus;
import dev.pushkin.jvmresearch.enterprise.external.ExternalApiClient;
import dev.pushkin.jvmresearch.enterprise.external.ExternalSignStatusResponse;
import dev.pushkin.jvmresearch.enterprise.kafka.BusinessEventSource;
import dev.pushkin.jvmresearch.enterprise.kafka.EnterpriseEventPublisher;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StatusPollingScheduler {

    private final OrderRepository repository;
    private final ExternalApiClient externalApiClient;
    private final EnterpriseEventPublisher eventPublisher;
    private final SandboxProperties properties;

    @Scheduled(fixedDelayString = "${sandbox.scheduler.status-polling-fixed-delay-ms}")
    public void pollExternalStatus() {
        if (!properties.scheduler().statusPollingEnabled()) {
            return;
        }

        repository.findByStatusOrderByUpdatedAtAsc(
                OrderStatus.WAITING_EXTERNAL_STATUS,
                Pageable.ofSize(properties.scheduler().statusPollingBatchSize())
        ).forEach(this::pollOne);
    }

    private void pollOne(OrderDocument order) {
        try {
            ExternalSignStatusResponse response = externalApiClient.getSignStatus(order.getExternalRequestId());
            if (response == null) {
                throw new IllegalStateException("External status response is null");
            }

            GoskeyProcessStatus externalStatus = GoskeyProcessStatus.fromExternal(response.status());
            if (externalStatus.isFinalSuccess()) {
                order.setStatus(OrderStatus.COMPLETED);
                order.getGoskeyProcess().setStatus(externalStatus);
            } else if (externalStatus.isFinalFailure()) {
                order.setStatus(OrderStatus.FAILED);
                order.getGoskeyProcess().setStatus(externalStatus);
                order.getGoskeyProcess().setFailMessage(response.failMessage());
            } else {
                order.getGoskeyProcess().setStatus(externalStatus);
            }

            order.addHistory(order.getStatus().name(), BusinessEventSource.STATUS_POLLING_SCHEDULER.value(), "External status polled", Instant.now());
            order.setUpdatedAt(Instant.now());
            OrderDocument saved = repository.save(order);
            eventPublisher.publishOrderStatusChanged(saved, BusinessEventSource.STATUS_POLLING_SCHEDULER);
            log.info("External status polled orderId={} externalStatus={} orderStatus={}", saved.getId(), externalStatus, saved.getStatus());
        } catch (RuntimeException ex) {
            log.warn("Status polling failed for orderId={}: {}", order.getId(), ex.getMessage());
            order.addHistory(OrderStatus.FAILED.name(), BusinessEventSource.STATUS_POLLING_SCHEDULER.value(), ex.getClass().getSimpleName(), Instant.now());
            order.setUpdatedAt(Instant.now());
            repository.save(order);
        }
    }
}
