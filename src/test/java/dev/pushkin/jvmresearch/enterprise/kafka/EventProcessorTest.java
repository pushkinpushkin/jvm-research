package dev.pushkin.jvmresearch.enterprise.kafka;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.pushkin.jvmresearch.enterprise.domain.*;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import dev.pushkin.jvmresearch.enterprise.service.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Map;
import java.util.Optional;

class EventProcessorTest {
    private OrderDocument order() {
        return OrderDocument.newOrder("order-1", ClientType.INDIVIDUAL, "client", Map.of());
    }

    private BusinessEvent event() {
        return new BusinessEvent(
                "event-1",
                "order-1",
                BusinessEventType.ORDER_STATUS_CHANGED,
                "COMPLETED",
                "now",
                Map.of());
    }

    @Test
    void failedSaveDoesNotPoisonDedupAndRedeliveryStagesDownstreamOnce() {
        var repo = mock(OrderRepository.class);
        var cache = new InMemoryBusinessEventDeduplicationService();
        var metrics = new ResearchMetrics(new SimpleMeterRegistry());
        var processor =
                new EventProcessor(
                        repo, new OrderLocks(), cache, new EnterpriseEventPublisher(), metrics);
        // A failed write is not present on re-read, exactly as in Mongo.
        var successful = order();
        when(repo.findById("order-1"))
                .thenReturn(Optional.of(order()), Optional.of(successful), Optional.of(successful));
        when(repo.save(any()))
                .thenThrow(new OptimisticLockingFailureException("injected"))
                .thenAnswer(i -> i.getArgument(0));
        assertThrows(
                OptimisticLockingFailureException.class, () -> processor.process(event(), true));
        assertFalse(cache.isProcessed("event-1"));
        processor.process(event(), true);
        processor.process(event(), true);
        assertEquals(1, successful.getProcessedBusinessEvents().size());
        assertEquals(1, successful.getPendingEvents().size());
        assertEquals("event-1:business", successful.getPendingEvents().getFirst().eventId());
        verify(repo, times(2)).save(any());
        assertEquals(1L, metrics.snapshot().get("events_consumed"));
    }

    @Test
    void persistedReceiptSurvivesEmptyCache() {
        var repo = mock(OrderRepository.class);
        var order = order();
        order.getProcessedBusinessEvents().add("event-1");
        when(repo.findById("order-1")).thenReturn(Optional.of(order));
        new EventProcessor(
                        repo,
                        new OrderLocks(),
                        new InMemoryBusinessEventDeduplicationService(),
                        new EnterpriseEventPublisher(),
                        new ResearchMetrics(new SimpleMeterRegistry()))
                .process(event(), true);
        verify(repo, never()).save(any());
    }

    @Test
    void missingOrderIsRetriedInsteadOfAcknowledged() {
        var repo = mock(OrderRepository.class);
        when(repo.findById("order-1")).thenReturn(Optional.empty());
        var cache = new InMemoryBusinessEventDeduplicationService();
        var processor =
                new EventProcessor(
                        repo,
                        new OrderLocks(),
                        cache,
                        new EnterpriseEventPublisher(),
                        new ResearchMetrics(new SimpleMeterRegistry()));
        assertThrows(IllegalStateException.class, () -> processor.process(event(), true));
        assertEquals(0, cache.size());
    }
}
