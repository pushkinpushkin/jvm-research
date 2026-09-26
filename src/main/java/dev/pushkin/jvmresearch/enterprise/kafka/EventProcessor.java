package dev.pushkin.jvmresearch.enterprise.kafka;

import dev.pushkin.jvmresearch.enterprise.domain.OrderStatus;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import dev.pushkin.jvmresearch.enterprise.service.OrderLocks;
import dev.pushkin.jvmresearch.enterprise.service.ResearchMetrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventProcessor {
    private final OrderRepository repository;
    private final OrderLocks locks;
    private final InMemoryBusinessEventDeduplicationService cache;
    private final EnterpriseEventPublisher publisher;
    private final ResearchMetrics metrics;

    public void process(BusinessEvent event, boolean statusEvent) {
        try {
            if (event == null
                    || event.eventId() == null
                    || event.eventId().isBlank()
                    || event.orderId() == null) throw new IllegalArgumentException("Invalid event");
            locks.withOrder(
                    event.orderId(),
                    () -> {
                        var order =
                                repository
                                        .findById(event.orderId())
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "Missing event order"));
                        if (cache.isProcessed(event.eventId())
                                || order.getProcessedBusinessEvents().contains(event.eventId())) {
                            metrics.increment("events_duplicate");
                            return null;
                        }
                        order.getProcessedBusinessEvents().add(event.eventId());
                        order.addHistory(
                                event.status(),
                                statusEvent
                                        ? BusinessEventSource.ORDER_STATUS_CONSUMER.value()
                                        : BusinessEventSource.BUSINESS_EVENT_CONSUMER.value(),
                                "Kafka event: " + event.type(),
                                Instant.now());
                        boolean downstream =
                                statusEvent && OrderStatus.COMPLETED.name().equals(event.status());
                        if (downstream)
                            publisher.publishBusinessEvent(
                                    order, BusinessEventType.ACCOUNT_FULLY_OPENED, event.eventId());
                        // Receipt, history, and downstream outbox are one Mongo document write.
                        repository.save(order);
                        cache.markProcessed(event);
                        metrics.increment("events_consumed");
                        if (downstream) metrics.increment("events_enqueued");
                        log.info(
                                "Business event occurred {} for orderId={} eventId={}",
                                event.type(),
                                event.orderId(),
                                event.eventId());
                        return null;
                    });
        } catch (RuntimeException ex) {
            metrics.increment("consumer_errors");
            throw ex;
        }
    }
}
