package dev.pushkin.jvmresearch.enterprise.service;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.external.ExternalMode;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ChaosPolicy {

    private final SandboxProperties properties;

    public ChaosPolicy(SandboxProperties properties) {
        this.properties = properties;
    }

    public ExternalMode externalMode(String key, String point) {
        if (!properties.chaos().enabled()) {
            return ExternalMode.OK;
        }

        int bucket = bucket(key, point, "external");
        int threshold = properties.chaos().externalApiLongDelayPercent();
        if (bucket < threshold) {
            return ExternalMode.LONG_DELAY;
        }

        threshold += properties.chaos().externalApiErrorPercent();
        if (bucket < threshold) {
            return ExternalMode.ERROR;
        }

        threshold += properties.chaos().externalApiMalformedPercent();
        if (bucket < threshold) {
            return ExternalMode.MALFORMED;
        }

        threshold += properties.chaos().externalApiSlowPercent();
        if (bucket < threshold) {
            return ExternalMode.SLOW;
        }

        return ExternalMode.OK;
    }

    public boolean shouldSimulateMongoConflict(String orderId, String point) {
        return enabledBucket(orderId, point, "mongo-conflict", properties.chaos().mongoConflictPercent());
    }

    public boolean shouldDuplicateKafkaEvent(String orderId, String eventType) {
        return enabledBucket(orderId, eventType, "kafka-duplicate", properties.chaos().kafkaDuplicatePercent());
    }

    private boolean enabledBucket(String key, String point, String salt, int percent) {
        return properties.chaos().enabled() && bucket(key, point, salt) < percent;
    }

    private int bucket(String key, String point, String salt) {
        return Math.floorMod(Objects.hash(key, point, salt), 100);
    }
}
