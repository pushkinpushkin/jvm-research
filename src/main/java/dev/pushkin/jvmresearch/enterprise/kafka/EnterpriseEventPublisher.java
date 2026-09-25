package dev.pushkin.jvmresearch.enterprise.kafka;

import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.repository.OrderBounds;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Stages an event in the order. The caller MUST save the order and event together. */
@Component
public class EnterpriseEventPublisher {
    public void publishOrderStatusChanged(OrderDocument order, BusinessEventSource source) {
        stage(
                order,
                new BusinessEvent(
                        UUID.randomUUID().toString(),
                        order.getId(),
                        BusinessEventType.ORDER_STATUS_CHANGED,
                        order.getStatus().name(),
                        Instant.now().toString(),
                        Map.of("source", source.value())));
    }

    public void publishBusinessEvent(OrderDocument order, BusinessEventType type, String parentId) {
        stage(
                order,
                new BusinessEvent(
                        parentId + ":business",
                        order.getId(),
                        type,
                        order.getStatus().name(),
                        Instant.now().toString(),
                        Map.of("clientType", order.getClientType())));
    }

    private void stage(OrderDocument order, BusinessEvent event) {
        if (order.getPendingEvents().size() >= OrderBounds.OUTBOX)
            throw new IllegalStateException("Outbox full");
        order.getPendingEvents().add(event);
    }
}
