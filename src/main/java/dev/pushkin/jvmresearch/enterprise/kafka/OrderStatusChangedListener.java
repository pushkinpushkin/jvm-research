package dev.pushkin.jvmresearch.enterprise.kafka;

import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.domain.OrderStatus;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderStatusChangedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusChangedListener.class);

    private final OrderRepository repository;
    private final EnterpriseEventPublisher eventPublisher;
    private final InMemoryBusinessEventDeduplicationService deduplicationService;

    public OrderStatusChangedListener(
            OrderRepository repository,
            EnterpriseEventPublisher eventPublisher,
            InMemoryBusinessEventDeduplicationService deduplicationService
    ) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.deduplicationService = deduplicationService;
    }

    @KafkaListener(topics = "${sandbox.kafka.order-status-topic}")
    public void onOrderStatusChanged(BusinessEvent event) {
        if (!deduplicationService.markProcessed(event)) {
            log.info("Duplicate Kafka status event skipped eventId={} orderId={} type={}", eventId(event), orderId(event), eventType(event));
            return;
        }

        repository.findById(event.orderId()).ifPresentOrElse(
                order -> processStatusEvent(order, event),
                () -> log.warn("Kafka status event points to missing order orderId={} eventId={}", event.orderId(), event.eventId())
        );
    }

    private void processStatusEvent(OrderDocument order, BusinessEvent event) {
        order.addHistory(
                event.status(),
                StatusEvents.SOURCE_ORDER_STATUS_CONSUMER,
                "Kafka status event consumed: " + event.type(),
                Instant.now()
        );
        order.setUpdatedAt(Instant.now());
        repository.save(order);

        if (StatusEvents.ORDER_STATUS_CHANGED.equals(event.type()) && OrderStatus.COMPLETED.name().equals(event.status())) {
            eventPublisher.publishBusinessEvent(order, StatusEvents.BUSINESS_EVENT_ACCOUNT_FULLY_OPENED);
        }
    }

    private String eventId(BusinessEvent event) {
        return event == null ? "n/a" : event.eventId();
    }

    private String orderId(BusinessEvent event) {
        return event == null ? "n/a" : event.orderId();
    }

    private String eventType(BusinessEvent event) {
        return event == null ? "n/a" : event.type();
    }
}
