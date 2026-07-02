package dev.pushkin.jvmresearch.enterprise.kafka;

import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import java.time.Instant;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BusinessEventListener {

    private static final Logger log = LoggerFactory.getLogger(BusinessEventListener.class);

    private final OrderRepository repository;
    private final InMemoryBusinessEventDeduplicationService deduplicationService;

    public BusinessEventListener(
            OrderRepository repository,
            InMemoryBusinessEventDeduplicationService deduplicationService
    ) {
        this.repository = repository;
        this.deduplicationService = deduplicationService;
    }

    @KafkaListener(topics = "${sandbox.kafka.business-event-topic}")
    public void onBusinessEvent(BusinessEvent event) {
        if (!deduplicationService.markProcessed(event)) {
            log.info("Duplicate business event skipped eventId={} orderId={} type={}", eventId(event), orderId(event), eventType(event));
            return;
        }

        repository.findById(event.orderId()).ifPresentOrElse(
                order -> processBusinessEvent(order, event),
                () -> log.warn("Business event points to missing order orderId={} eventId={}", event.orderId(), event.eventId())
        );
    }

    private void processBusinessEvent(OrderDocument order, BusinessEvent event) {
        if (order.getProcessedBusinessEvents() == null) {
            order.setProcessedBusinessEvents(new ArrayList<>());
        }

        if (order.getProcessedBusinessEvents().contains(event.eventId())) {
            log.info("Business event already stored in order document eventId={} orderId={}", event.eventId(), event.orderId());
            return;
        }

        order.getProcessedBusinessEvents().add(event.eventId());
        order.addHistory(
                event.status(),
                StatusEvents.SOURCE_BUSINESS_EVENT_CONSUMER,
                "Business event processed: " + event.type(),
                Instant.now()
        );
        order.setUpdatedAt(Instant.now());
        repository.save(order);
        log.info("Business event occurred {} for orderId={} eventId={}", event.type(), event.orderId(), event.eventId());
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
