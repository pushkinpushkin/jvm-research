package dev.pushkin.jvmresearch.enterprise.kafka;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import java.time.*;

class DedupBoundsTest {
    @Test
    void capacityAndExpiryAreBounded() {
        var clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L);
        var cache = new InMemoryBusinessEventDeduplicationService(3, Duration.ofSeconds(1), clock);
        for (int i = 0; i < 100; i++) cache.markProcessed("event-" + i);
        assertEquals(3, cache.size());
        assertFalse(cache.isProcessed("event-0"));
        assertTrue(cache.isProcessed("event-99"));
        when(clock.millis()).thenReturn(1000L);
        assertEquals(0, cache.size());
        assertTrue(cache.markProcessed("event-99"));
    }
}
