package dev.pushkin.jvmresearch.enterprise.service;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.external.ExternalMode;
import org.springframework.stereotype.Component;

@Component
public class TrafficProfile {

    private final SandboxProperties properties;

    public TrafficProfile(SandboxProperties properties) {
        this.properties = properties;
    }

    public ExternalMode externalMode(String key, String point) {
        if (!properties.profile().enabled()) {
            return ExternalMode.OK;
        }

        int value = value(key, point, "external");
        int limit = properties.profile().externalApiLongDelayPercent();
        if (value < limit) {
            return ExternalMode.LONG_DELAY;
        }

        limit += properties.profile().externalApiErrorPercent();
        if (value < limit) {
            return ExternalMode.ERROR;
        }

        limit += properties.profile().externalApiInvalidPercent();
        if (value < limit) {
            return ExternalMode.BAD_RESPONSE;
        }

        limit += properties.profile().externalApiSlowPercent();
        if (value < limit) {
            return ExternalMode.SLOW;
        }

        return ExternalMode.OK;
    }

    public boolean mongoConflict(String orderId, String point) {
        return properties.profile().enabled()
                && value(orderId, point, "mongo") < properties.profile().mongoConflictPercent();
    }

    public boolean duplicateKafkaEvent(String orderId, String eventType) {
        return properties.profile().enabled()
                && value(orderId, eventType, "kafka") < properties.profile().kafkaDuplicatePercent();
    }

    private int value(String key, String point, String salt) {
        return Math.floorMod((key + point + salt).hashCode(), 100);
    }
}
