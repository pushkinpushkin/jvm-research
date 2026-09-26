package dev.pushkin.jvmresearch.enterprise.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.domain.*;
import dev.pushkin.jvmresearch.enterprise.external.*;
import dev.pushkin.jvmresearch.enterprise.kafka.EnterpriseEventPublisher;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Map;
import java.util.Optional;

class OrderFlowServiceTest {
    private final OrderRepository repository = mock(OrderRepository.class);
    private final ExternalApiClient external = mock(ExternalApiClient.class);
    private final ResearchMetrics metrics = new ResearchMetrics(new SimpleMeterRegistry());

    private OrderFlowService flow() {
        var config =
                new SandboxProperties(
                        null,
                        new SandboxProperties.Profile(false, 0, 0, 0, 0, 0, 0),
                        null,
                        null,
                        null);
        return new OrderFlowService(
                repository,
                external,
                new DtoMapperService(),
                new EnterpriseEventPublisher(),
                new TrafficProfile(config),
                new OrderLocks(),
                metrics);
    }

    private OrderDocument order() {
        var order = OrderDocument.newOrder("order-1", ClientType.INDIVIDUAL, "client", Map.of());
        order.setVersion(1L);
        return order;
    }

    @Test
    void unexpectedSaveFailureDoesNotTryToOverwriteWithFailedDocument() {
        when(repository.findById("order-1")).thenReturn(Optional.of(order()));
        when(external.getFnsData("order-1"))
                .thenReturn(new ExternalFnsResponse("order-1", "7700", "Fns", "FNS_OK", "LOW"));
        when(repository.save(any()))
                .thenThrow(new OptimisticLockingFailureException("concurrent update"));
        assertThrows(OptimisticLockingFailureException.class, () -> flow().process("order-1"));
        verify(repository, times(1)).save(any());
        assertEquals(1L, metrics.snapshot().get("http_unexpected"));
        assertEquals(0L, metrics.snapshot().get("events_enqueued"));
    }

    @Test
    void expectedFaultIsPersistedWithOutboxAndDistinctOutcome() {
        var order = order();
        when(repository.findById("order-1")).thenReturn(Optional.of(order));
        when(external.getFnsData("order-1"))
                .thenThrow(
                        new ExpectedExternalFault(ExternalMode.ERROR, new RuntimeException("503")));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        var response = flow().process("order-1");
        assertEquals("expected_fault", response.outcome());
        assertEquals("FAILED", response.status());
        assertEquals(1, order.getPendingEvents().size());
        assertEquals(1L, metrics.snapshot().get("http_expected_fault"));
    }

    @Test
    void retrySkipsAnOrderWhichWasCompletedAfterTheSchedulerQuery() {
        var order = order();
        order.setStatus(OrderStatus.COMPLETED);
        when(repository.findById("order-1")).thenReturn(Optional.of(order));
        flow().retry("order-1");
        verifyNoInteractions(external);
        verify(repository, never()).save(any());
    }
}
