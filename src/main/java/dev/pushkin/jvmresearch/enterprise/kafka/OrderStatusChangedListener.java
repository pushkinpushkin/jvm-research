package dev.pushkin.jvmresearch.enterprise.kafka;

import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.domain.OrderStatus;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusChangedListener {

    private final OrderRepository repository;
    private final EnterpriseEventPublisher eventPublisher;
    private final InMemoryBusinessEventDeduplicationService deduplicationService;

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
                BusinessEventSource.ORDER_STATUS_CONSUMER.value(),
                "Kafka status event consumed: " + event.type(),
                Instant.now()
        );
        order.setUpdatedAt(Instant.now());
        repository.save(order);

        if (BusinessEventType.ORDER_STATUS_CHANGED == event.type() && OrderStatus.COMPLETED.name().equals(event.status())) {
            eventPublisher.publishBusinessEvent(order, BusinessEventType.ACCOUNT_FULLY_OPENED);
        }
    }

    private String eventId(BusinessEvent event) {
        return event == null ? "n/a" : event.eventId();
    }

    private String orderId(BusinessEvent event) {
        return event == null ? "n/a" : event.orderId();
    }

    private String eventType(BusinessEvent event) {
        return event == null ? "n/a" : event.type().name();
    }
}
