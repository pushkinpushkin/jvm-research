package dev.pushkin.jvmresearch.enterprise.kafka;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.service.ChaosPolicy;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class EnterpriseEventPublisher {

    private final KafkaTemplate<String, BusinessEvent> kafkaTemplate;
    private final SandboxProperties properties;
    private final ChaosPolicy chaosPolicy;

    public EnterpriseEventPublisher(
            KafkaTemplate<String, BusinessEvent> kafkaTemplate,
            SandboxProperties properties,
            ChaosPolicy chaosPolicy
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.chaosPolicy = chaosPolicy;
    }

    public void publishOrderStatusChanged(OrderDocument order, String source) {
        BusinessEvent event = new BusinessEvent(
                UUID.randomUUID().toString(),
                order.getId(),
                "ORDER_STATUS_CHANGED",
                order.getStatus().name(),
                Instant.now().toString(),
                Map.of("source", source, "version", String.valueOf(order.getVersion()))
        );
        publishWithOptionalDuplicate(properties.kafka().orderStatusTopic(), order.getId(), event);
    }

    public void publishBusinessEvent(OrderDocument order, String businessEventType) {
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
        if (chaosPolicy.shouldDuplicateKafkaEvent(key, event.type())) {
            kafkaTemplate.send(topic, key, event);
        }
    }
}
