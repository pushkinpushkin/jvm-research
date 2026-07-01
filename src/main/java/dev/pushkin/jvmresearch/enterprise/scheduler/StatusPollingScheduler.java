package dev.pushkin.jvmresearch.enterprise.scheduler;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.domain.OrderStatus;
import dev.pushkin.jvmresearch.enterprise.external.ExternalApiClient;
import dev.pushkin.jvmresearch.enterprise.external.ExternalSignStatusResponse;
import dev.pushkin.jvmresearch.enterprise.kafka.EnterpriseEventPublisher;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StatusPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatusPollingScheduler.class);

    private final OrderRepository repository;
    private final ExternalApiClient externalApiClient;
    private final EnterpriseEventPublisher eventPublisher;
    private final SandboxProperties properties;

    public StatusPollingScheduler(
            OrderRepository repository,
            ExternalApiClient externalApiClient,
            EnterpriseEventPublisher eventPublisher,
            SandboxProperties properties
    ) {
        this.repository = repository;
        this.externalApiClient = externalApiClient;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

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
                throw new IllegalStateException("External sign status response is null");
            }

            if ("DOCS_SIGNED".equals(response.status())) {
                order.setStatus(OrderStatus.COMPLETED);
                order.getGoskeyProcess().setStatus(response.status());
            } else if (response.status() != null && response.status().endsWith("FAILED")) {
                order.setStatus(OrderStatus.FAILED);
                order.getGoskeyProcess().setStatus(response.status());
                order.getGoskeyProcess().setFailMessage(response.failMessage());
            } else {
                order.getGoskeyProcess().setStatus(response.status());
            }

            order.addHistory(order.getStatus().name(), "status-polling-scheduler", "External sign status polled", Instant.now());
            order.setUpdatedAt(Instant.now());
            OrderDocument saved = repository.save(order);
            eventPublisher.publishOrderStatusChanged(saved, "status-polling-scheduler");
        } catch (RuntimeException ex) {
            log.warn("Status polling failed for orderId={}: {}", order.getId(), ex.getMessage());
            order.addHistory(OrderStatus.FAILED.name(), "status-polling-scheduler", ex.getClass().getSimpleName(), Instant.now());
            order.setUpdatedAt(Instant.now());
            repository.save(order);
        }
    }
}
