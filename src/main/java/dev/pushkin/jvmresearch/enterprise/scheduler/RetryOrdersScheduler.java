package dev.pushkin.jvmresearch.enterprise.scheduler;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import dev.pushkin.jvmresearch.enterprise.service.OrderFlowService;
import dev.pushkin.jvmresearch.enterprise.service.ResearchMetrics;

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
    private final OrderFlowService flow;
    private final SandboxProperties properties;
    private final ResearchMetrics metrics;

    @Scheduled(fixedDelayString = "${sandbox.scheduler.retry-fixed-delay-ms}")
    public void retryFailedOrders() {
        if (!properties.scheduler().retryEnabled()) return;
        metrics.increment("scheduler_runs");
        try {
            for (var order :
                    repository.findRetryable(
                            Pageable.ofSize(properties.scheduler().retryBatchSize()))) {
                try {
                    flow.retry(order.getId());
                } catch (RuntimeException ex) {
                    metrics.increment("scheduler_errors");
                    log.warn("Retry failed", ex);
                }
            }
        } catch (RuntimeException ex) {
            metrics.increment("scheduler_errors");
            log.warn("Retry query failed", ex);
        }
    }
}
