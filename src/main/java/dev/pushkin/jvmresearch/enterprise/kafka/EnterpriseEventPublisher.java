package dev.pushkin.jvmresearch.enterprise.kafka;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.service.TrafficProfile;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnterpriseEventPublisher {

    private final KafkaTemplate<String, BusinessEvent> kafkaTemplate;
    private final SandboxProperties properties;
    private final TrafficProfile trafficProfile;

    public void publishOrderStatusChanged(OrderDocument order, BusinessEventSource source) {
        BusinessEvent event = new BusinessEvent(
                UUID.randomUUID().toString(),
                order.getId(),
                BusinessEventType.ORDER_STATUS_CHANGED,
                order.getStatus().name(),
                Instant.now().toString(),
                Map.of("source", source.value(), "version", String.valueOf(order.getVersion()))
        );
        publishWithOptionalDuplicate(properties.kafka().orderStatusTopic(), order.getId(), event);
    }

    public void publishBusinessEvent(OrderDocument order, BusinessEventType businessEventType) {
        BusinessEvent event = new BusinessEvent(
                UUID.randomUUID().toString(),
                order.getId(),
                businessEventType,
                order.getStatus().name(),
                Instant.now().toString(),
                Map.of("clientType", order.getClientType())
        );
        publishWithOptionalDuplicate(properties.kafka().businessEventTopic(), order.getId(), event);
    }

    private void publishWithOptionalDuplicate(String topic, String key, BusinessEvent event) {
        kafkaTemplate.send(topic, key, event);
        log.info("Kafka event published topic={} key={} eventId={} type={} status={}", topic, key, event.eventId(), event.type(), event.status());
        if (trafficProfile.duplicateKafkaEvent(key, event.type())) {
            kafkaTemplate.send(topic, key, event);
            log.info("Synthetic Kafka duplicate published topic={} key={} eventId={} type={}", topic, key, event.eventId(), event.type());
        }
    }
}
