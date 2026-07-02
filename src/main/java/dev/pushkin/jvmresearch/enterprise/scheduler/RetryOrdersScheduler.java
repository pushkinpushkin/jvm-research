package dev.pushkin.jvmresearch.enterprise.scheduler;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.domain.OrderStatus;
import dev.pushkin.jvmresearch.enterprise.kafka.EnterpriseEventPublisher;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetryOrdersScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryOrdersScheduler.class);

    private final OrderRepository repository;
    private final EnterpriseEventPublisher eventPublisher;
    private final SandboxProperties properties;

    public RetryOrdersScheduler(OrderRepository repository, EnterpriseEventPublisher eventPublisher, SandboxProperties properties) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

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
        order.addHistory(OrderStatus.NEW.name(), "retry-scheduler", "Failed order moved to retry", Instant.now());
        order.setUpdatedAt(Instant.now());
        OrderDocument saved = repository.save(order);
        eventPublisher.publishOrderStatusChanged(saved, "retry-scheduler");
    }
}
