package dev.pushkin.jvmresearch.enterprise.kafka;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.domain.*;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import dev.pushkin.jvmresearch.enterprise.service.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;

class OutboxDispatcherTest {
    @Test
    @SuppressWarnings("unchecked")
    void failedBrokerAckKeepsEventForNextDispatch() {
        var repo = mock(OrderRepository.class);
        KafkaTemplate<String, BusinessEvent> kafka = mock(KafkaTemplate.class);
        var config =
                new SandboxProperties(
                        null,
                        new SandboxProperties.Profile(false, 0, 0, 0, 0, 0, 0),
                        null,
                        new SandboxProperties.Kafka("status", "business"),
                        null);
        var order = OrderDocument.newOrder("order-1", ClientType.INDIVIDUAL, "client", Map.of());
        new EnterpriseEventPublisher()
                .publishOrderStatusChanged(order, BusinessEventSource.HTTP_PROCESS);
        String id = order.getPendingEvents().getFirst().eventId();
        when(repo.findPendingEvents(any())).thenReturn(List.of(order));
        when(repo.findById("order-1")).thenReturn(Optional.of(order));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(kafka.send(eq("status"), eq("order-1"), any(BusinessEvent.class)))
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new IllegalStateException("broker unavailable")))
                .thenReturn(CompletableFuture.completedFuture(null));
        var metrics = new ResearchMetrics(new SimpleMeterRegistry());
        var dispatcher =
                new OutboxDispatcher(
                        repo, new OrderLocks(), kafka, config, new TrafficProfile(config), metrics);
        dispatcher.dispatch();
        assertEquals(id, order.getPendingEvents().getFirst().eventId());
        verify(repo, never()).save(any());
        dispatcher.dispatch();
        assertTrue(order.getPendingEvents().isEmpty());
        assertEquals(1L, metrics.snapshot().get("events_published"));
        assertEquals(1L, metrics.snapshot().get("outbox_errors"));
    }
}
