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
public class OrderStatusChangedConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusChangedConsumer.class);

    private final OrderRepository repository;
    private final EnterpriseEventPublisher eventPublisher;

    public OrderStatusChangedConsumer(OrderRepository repository, EnterpriseEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(topics = "${sandbox.kafka.order-status-topic}")
    public void handle(BusinessEvent event) {
        repository.findById(event.orderId()).ifPresentOrElse(order -> consumeExistingOrder(event, order), () -> log.warn(
                "Order status event skipped because order was not found, orderId={}, eventId={}",
                event.orderId(),
                event.eventId()
        ));
    }

    private void consumeExistingOrder(BusinessEvent event, OrderDocument order) {
        order.addHistory(event.status(), "kafka-consumer", "Consumed " + event.type(), Instant.now());
        order.setUpdatedAt(Instant.now());
        repository.save(order);

        if (order.getStatus() == OrderStatus.COMPLETED) {
            eventPublisher.publishBusinessEvent(order, "ACCOUNT_FULLY_OPENED");
        }
    }
}
