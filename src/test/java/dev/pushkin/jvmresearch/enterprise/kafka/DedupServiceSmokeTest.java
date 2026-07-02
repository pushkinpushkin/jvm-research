package dev.pushkin.jvmresearch.enterprise.kafka;

import org.junit.jupiter.api.Test;

class DedupServiceSmokeTest {

    @Test
    void smoke() {
        InMemoryBusinessEventDeduplicationService service = new InMemoryBusinessEventDeduplicationService();
        assert service.markProcessed("event-1");
        assert !service.markProcessed("event-1");
    }
}
