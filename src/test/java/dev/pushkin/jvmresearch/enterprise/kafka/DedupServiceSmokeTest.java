package dev.pushkin.jvmresearch.enterprise.kafka;

import org.junit.jupiter.api.Test;

class DedupServiceSmokeTest {

    @Test
    void smoke() {
        InMemoryBusinessEventDeduplicationService service = new InMemoryBusinessEventDeduplicationService();
        if (!service.markProcessed("event-1")) {
            throw new AssertionError("First event should be accepted");
        }
        if (service.markProcessed("event-1")) {
            throw new AssertionError("Duplicate event should be rejected");
        }
        if (!service.isProcessed("event-1") || service.size() != 1) {
            throw new AssertionError("Event should stay stored once");
        }
    }
}
