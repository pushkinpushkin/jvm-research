package dev.pushkin.jvmresearch.enterprise.kafka;

import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BusinessEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(BusinessEventConsumer.class);

    private final OrderRepository repository;

    public BusinessEventConsumer(OrderRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "${sandbox.kafka.business-event-topic}")
    public void handle(BusinessEvent event) {
        repository.findById(event.orderId()).ifPresent(order -> consume(event, order));
    }

    private void consume(BusinessEvent event, OrderDocument order) {
        String dedupKey = event.type() + ":" + event.orderId();
        if (order.getProcessedBusinessEvents().contains(dedupKey)) {
            log.info("Duplicate business event skipped type={} orderId={} eventId={}", event.type(), event.orderId(), event.eventId());
            return;
        }

        order.getProcessedBusinessEvents().add(dedupKey);
        order.addHistory(event.type(), "business-event-consumer", "Business event occurred", Instant.now());
        order.setUpdatedAt(Instant.now());
        repository.save(order);
        log.info("Business event occurred type={} orderId={} eventId={}", event.type(), event.orderId(), event.eventId());
    }
}
