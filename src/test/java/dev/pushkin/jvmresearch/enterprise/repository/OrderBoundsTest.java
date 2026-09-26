package dev.pushkin.jvmresearch.enterprise.repository;

import static org.junit.jupiter.api.Assertions.*;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.domain.*;
import dev.pushkin.jvmresearch.enterprise.kafka.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

class OrderBoundsTest {
    @Test
    void everySaveTrimsHistoryAndReceiptsButNeverSilentlyDropsOutbox() {
        var bounds =
                new OrderBounds(
                        new SandboxProperties(
                                new SandboxProperties.Flow(40, 60, 300), null, null, null, null));
        var order = OrderDocument.newOrder("id", ClientType.INDIVIDUAL, "client", Map.of());
        for (int i = 0; i < 10_000; i++) {
            order.addHistory("NEW", "consumer", "event", Instant.EPOCH);
            order.getProcessedBusinessEvents().add("id-" + i);
            bounds.onBeforeConvert(order, "orders");
        }
        assertEquals(40, order.getHistory().size());
        assertEquals(1024, order.getProcessedBusinessEvents().size());
        assertEquals("id-9999", order.getProcessedBusinessEvents().getLast());
        var publisher = new EnterpriseEventPublisher();
        for (int i = 0; i < 256; i++)
            publisher.publishOrderStatusChanged(order, BusinessEventSource.HTTP_PROCESS);
        assertThrows(
                IllegalStateException.class,
                () -> publisher.publishOrderStatusChanged(order, BusinessEventSource.HTTP_PROCESS));
        assertEquals(256, order.getPendingEvents().size());
    }
}
