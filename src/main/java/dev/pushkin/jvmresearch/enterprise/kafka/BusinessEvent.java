package dev.pushkin.jvmresearch.enterprise.kafka;

import java.util.Map;

public record BusinessEvent(
        String eventId,
        String orderId,
        String type,
        String status,
        String occurredAt,
        Map<String, Object> payload
) {
}
