package dev.pushkin.jvmresearch.enterprise.service;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.domain.ClientType;
import dev.pushkin.jvmresearch.enterprise.domain.FnsProcessStatus;
import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.domain.OrderStatus;
import dev.pushkin.jvmresearch.enterprise.external.ExternalApiClient;
import dev.pushkin.jvmresearch.enterprise.external.ExternalFnsResponse;
import dev.pushkin.jvmresearch.enterprise.kafka.BusinessEventSource;
import dev.pushkin.jvmresearch.enterprise.kafka.EnterpriseEventPublisher;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderFlowService {

    private final OrderRepository repository;
    private final ExternalApiClient externalApiClient;
    private final DtoMapperService dtoMapperService;
    private final EnterpriseEventPublisher eventPublisher;
    private final TrafficProfile trafficProfile;
    private final PayloadBuilder payloadBuilder;
    private final SandboxProperties properties;

    public ProcessOrderResponse process(String orderId) {
        log.info("Order processing started orderId={}", orderId);
        OrderDocument order = repository.findById(orderId)
                .orElseGet(() -> OrderDocument.newOrder(orderId, ClientType.INDIVIDUAL, "client-" + orderId, payloadBuilder.createPayload(orderId)));

        Instant now = Instant.now();
        order.setStatus(OrderStatus.PROCESSING);
        order.setUpdatedAt(now);
        order.addHistory(OrderStatus.PROCESSING.name(), BusinessEventSource.HTTP.value(), "HTTP flow started", now);

        try {
            ExternalFnsResponse fnsResponse = Objects.requireNonNull(
                    externalApiClient.getFnsData(orderId),
                    "External FNS response is null"
            );
            Map<String, Object> mappedPayload = dtoMapperService.mergeExternalFnsData(order, fnsResponse);
            FnsProcessStatus fnsStatus = FnsProcessStatus.fromExternal(fnsResponse.externalStatus());

            order.getFnsProcess().setStatus(fnsStatus);
            order.getFnsProcess().setRegisteringFns(fnsResponse.registeringFns());
            order.getFnsProcess().setRegisteringFnsName(fnsResponse.registeringFnsName());
            order.getFnsProcess().setAttempts(order.getFnsProcess().getAttempts() + 1);
            order.setPayload(mappedPayload);
            order.setStatus(fnsStatus.isSuccessful() ? OrderStatus.WAITING_EXTERNAL_STATUS : OrderStatus.PROCESSING);
            order.setUpdatedAt(Instant.now());
            order.addHistory(order.getStatus().name(), BusinessEventSource.EXTERNAL_API.value(), "FNS data mapped", Instant.now());

            if (trafficProfile.mongoConflict(orderId, BusinessEventSource.HTTP_PROCESS.value())) {
                throw new OptimisticLockingFailureException("Synthetic Mongo optimistic locking conflict for " + orderId);
            }

            trimHistory(order);
            OrderDocument saved = repository.save(order);
            eventPublisher.publishOrderStatusChanged(saved, BusinessEventSource.HTTP_PROCESS);
            log.info("Order processing finished orderId={} status={} version={}", saved.getId(), saved.getStatus(), version(saved));
            return new ProcessOrderResponse(saved.getId(), saved.getStatus().name(), "processed", version(saved));
        } catch (RuntimeException ex) {
            log.warn("Order processing failed orderId={} errorType={} message={}", orderId, ex.getClass().getSimpleName(), sanitize(ex.getMessage()));
            OrderDocument failed = saveFailed(order, ex, BusinessEventSource.HTTP_PROCESS);
            eventPublisher.publishOrderStatusChanged(failed, BusinessEventSource.HTTP_PROCESS_FAILED);
            return new ProcessOrderResponse(failed.getId(), failed.getStatus().name(), sanitize(ex.getMessage()), version(failed));
        }
    }

    private OrderDocument saveFailed(OrderDocument order, RuntimeException ex, BusinessEventSource source) {
        order.setStatus(OrderStatus.FAILED);
        order.getFnsProcess().setFailMessage(sanitize(ex.getMessage()));
        order.addHistory(OrderStatus.FAILED.name(), source.value(), sanitize(ex.getClass().getSimpleName() + ": " + ex.getMessage()), Instant.now());
        order.setUpdatedAt(Instant.now());
        trimHistory(order);
        return repository.save(order);
    }

    private void trimHistory(OrderDocument order) {
        int maxHistoryItems = properties.flow().historyItems();
        if (order.getHistory().size() <= maxHistoryItems) {
            return;
        }
        int fromIndex = order.getHistory().size() - maxHistoryItems;
        order.setHistory(new ArrayList<>(order.getHistory().subList(fromIndex, order.getHistory().size())));
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "n/a";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private long version(OrderDocument order) {
        return order.getVersion() == null ? 0L : order.getVersion();
    }
}
