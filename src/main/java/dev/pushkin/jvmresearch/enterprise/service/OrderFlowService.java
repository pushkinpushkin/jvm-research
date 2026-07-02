package dev.pushkin.jvmresearch.enterprise.service;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.domain.OrderStatus;
import dev.pushkin.jvmresearch.enterprise.external.ExternalApiClient;
import dev.pushkin.jvmresearch.enterprise.external.ExternalFnsResponse;
import dev.pushkin.jvmresearch.enterprise.kafka.EnterpriseEventPublisher;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class OrderFlowService {

    private final OrderRepository repository;
    private final ExternalApiClient externalApiClient;
    private final DtoMapperService dtoMapperService;
    private final EnterpriseEventPublisher eventPublisher;
    private final TrafficProfile trafficProfile;
    private final PayloadBuilder payloadBuilder;
    private final SandboxProperties properties;

    public OrderFlowService(
            OrderRepository repository,
            ExternalApiClient externalApiClient,
            DtoMapperService dtoMapperService,
            EnterpriseEventPublisher eventPublisher,
            TrafficProfile trafficProfile,
            PayloadBuilder payloadBuilder,
            SandboxProperties properties
    ) {
        this.repository = repository;
        this.externalApiClient = externalApiClient;
        this.dtoMapperService = dtoMapperService;
        this.eventPublisher = eventPublisher;
        this.trafficProfile = trafficProfile;
        this.payloadBuilder = payloadBuilder;
        this.properties = properties;
    }

    public ProcessOrderResponse process(String orderId) {
        OrderDocument order = repository.findById(orderId)
                .orElseGet(() -> OrderDocument.newOrder(orderId, "INDIVIDUAL", "client-" + orderId, payloadBuilder.createPayload(orderId)));

        Instant now = Instant.now();
        order.setStatus(OrderStatus.PROCESSING);
        order.setUpdatedAt(now);
        order.addHistory(OrderStatus.PROCESSING.name(), "http", "HTTP flow started", now);

        try {
            ExternalFnsResponse fnsResponse = Objects.requireNonNull(
                    externalApiClient.getFnsData(orderId),
                    "External FNS response is null"
            );
            Map<String, Object> mappedPayload = dtoMapperService.mergeExternalFnsData(order, fnsResponse);

            order.getFnsProcess().setStatus(fnsResponse.externalStatus());
            order.getFnsProcess().setRegisteringFns(fnsResponse.registeringFns());
            order.getFnsProcess().setRegisteringFnsName(fnsResponse.registeringFnsName());
            order.getFnsProcess().setAttempts(order.getFnsProcess().getAttempts() + 1);
            order.setPayload(mappedPayload);
            order.setStatus("FNS_OK".equals(fnsResponse.externalStatus()) ? OrderStatus.WAITING_EXTERNAL_STATUS : OrderStatus.PROCESSING);
            order.setUpdatedAt(Instant.now());
            order.addHistory(order.getStatus().name(), "external-api", "FNS data mapped", Instant.now());

            if (trafficProfile.mongoConflict(orderId, "http-process")) {
                throw new OptimisticLockingFailureException("Synthetic Mongo optimistic locking conflict for " + orderId);
            }

            trimHistory(order);
            OrderDocument saved = repository.save(order);
            eventPublisher.publishOrderStatusChanged(saved, "http-process");
            return new ProcessOrderResponse(saved.getId(), saved.getStatus().name(), "processed", version(saved));
        } catch (RuntimeException ex) {
            OrderDocument failed = saveFailed(order, ex, "http-process");
            eventPublisher.publishOrderStatusChanged(failed, "http-process-failed");
            return new ProcessOrderResponse(failed.getId(), failed.getStatus().name(), sanitize(ex.getMessage()), version(failed));
        }
    }

    private OrderDocument saveFailed(OrderDocument order, RuntimeException ex, String source) {
        order.setStatus(OrderStatus.FAILED);
        order.getFnsProcess().setFailMessage(sanitize(ex.getMessage()));
        order.addHistory(OrderStatus.FAILED.name(), source, sanitize(ex.getClass().getSimpleName() + ": " + ex.getMessage()), Instant.now());
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
