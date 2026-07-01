package dev.pushkin.jvmresearch.enterprise.service;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PayloadFactory {

    private final SandboxProperties properties;

    public PayloadFactory(SandboxProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> createPayload(String orderId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < properties.flow().payloadEntries(); i++) {
            payload.put("field_" + i, value(orderId, i));
        }
        payload.put("nested", Map.of(
                "orderId", orderId,
                "clientSegment", orderId.hashCode() % 2 == 0 ? "mass" : "premium",
                "synthetic", true
        ));
        return payload;
    }

    private String value(String orderId, int index) {
        String base = orderId + "-payload-" + index + "-";
        return base + "x".repeat(Math.max(1, properties.flow().payloadValueSize() - base.length()));
    }
}
