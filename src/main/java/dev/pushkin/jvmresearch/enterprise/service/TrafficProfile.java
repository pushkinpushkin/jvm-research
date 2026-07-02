package dev.pushkin.jvmresearch.enterprise.service;

import dev.pushkin.jvmresearch.enterprise.external.ExternalMode;
import org.springframework.stereotype.Component;

@Component
public class TrafficProfile {

    public ExternalMode externalMode(String key, String point) {
        return ExternalMode.OK;
    }

    public boolean mongoConflict(String orderId, String point) {
        return false;
    }

    public boolean duplicateKafkaEvent(String orderId, String eventType) {
        return false;
    }
}
