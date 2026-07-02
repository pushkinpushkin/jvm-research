package dev.pushkin.jvmresearch.enterprise.scheduler;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.domain.OrderStatus;
import dev.pushkin.jvmresearch.enterprise.kafka.BusinessEventSource;
import dev.pushkin.jvmresearch.enterprise.kafka.EnterpriseEventPublisher;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetryOrdersScheduler {

    private final OrderRepository repository;
    private final EnterpriseEventPublisher eventPublisher;
    private final SandboxProperties properties;

    @Scheduled(fixedDelayString = "${sandbox.scheduler.retry-fixed-delay-ms}")
    public void retryFailedOrders() {
        if (!properties.scheduler().retryEnabled()) {
            return;
        }

        repository.findByStatusOrderByUpdatedAtAsc(
                OrderStatus.FAILED,
                Pageable.ofSize(properties.scheduler().retryBatchSize())
        ).forEach(this::retryOne);
    }

    private void retryOne(OrderDocument order) {
        if (order.getFnsProcess().getAttempts() >= 3) {
            return;
        }

        log.info("Retrying failed order orderId={} attempts={}", order.getId(), order.getFnsProcess().getAttempts());
        order.setStatus(OrderStatus.NEW);
        order.getFnsProcess().setAttempts(order.getFnsProcess().getAttempts() + 1);
        order.addHistory(OrderStatus.NEW.name(), BusinessEventSource.RETRY_SCHEDULER.value(), "Failed order moved to retry", Instant.now());
        order.setUpdatedAt(Instant.now());
        OrderDocument saved = repository.save(order);
        eventPublisher.publishOrderStatusChanged(saved, BusinessEventSource.RETRY_SCHEDULER);
    }
}
