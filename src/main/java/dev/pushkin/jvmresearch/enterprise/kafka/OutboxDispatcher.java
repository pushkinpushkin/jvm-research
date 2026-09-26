package dev.pushkin.jvmresearch.enterprise.kafka;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import dev.pushkin.jvmresearch.enterprise.service.OrderLocks;
import dev.pushkin.jvmresearch.enterprise.service.ResearchMetrics;
import dev.pushkin.jvmresearch.enterprise.service.TrafficProfile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {
    private final OrderRepository repository;
    private final OrderLocks locks;
    private final KafkaTemplate<String, BusinessEvent> kafka;
    private final SandboxProperties properties;
    private final TrafficProfile traffic;
    private final ResearchMetrics metrics;

    @Scheduled(fixedDelayString = "${sandbox.kafka.outbox-delay-ms:200}")
    public void dispatch() {
        try {
            for (var candidate : repository.findPendingEvents(Pageable.ofSize(100))) {
                try {
                    locks.withOrder(
                            candidate.getId(),
                            () -> {
                                var order = repository.findById(candidate.getId()).orElseThrow();
                                while (!order.getPendingEvents().isEmpty()) {
                                    BusinessEvent event = order.getPendingEvents().getFirst();
                                    String topic =
                                            event.type() == BusinessEventType.ORDER_STATUS_CHANGED
                                                    ? properties.kafka().orderStatusTopic()
                                                    : properties.kafka().businessEventTopic();
                                    send(topic, event);
                                    if (traffic.duplicateKafkaEvent(order.getId(), event.type())) {
                                        send(topic, event);
                                        metrics.increment("synthetic_duplicates");
                                    }
                                    order.getPendingEvents().removeFirst();
                                    order = repository.save(order);
                                    metrics.increment("events_published");
                                }
                                return null;
                            });
                } catch (RuntimeException ex) {
                    metrics.increment("outbox_errors");
                    log.warn("Outbox dispatch failed orderId={}", candidate.getId(), ex);
                }
            }
        } catch (RuntimeException ex) {
            metrics.increment("outbox_errors");
            log.warn("Outbox query failed", ex);
        }
    }

    private void send(String topic, BusinessEvent event) {
        try {
            kafka.send(topic, event.orderId(), event).get(10, TimeUnit.SECONDS);
            metrics.increment("outbox_deliveries");
            log.info("Kafka event published topic={} eventId={}", topic, event.eventId());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka send interrupted", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Kafka send failed", ex);
        }
    }
}
