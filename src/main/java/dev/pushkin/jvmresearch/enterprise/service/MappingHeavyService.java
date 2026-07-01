package dev.pushkin.jvmresearch.enterprise.service;

import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.external.ExternalFnsResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MappingHeavyService {

    public Map<String, Object> mergeExternalFnsData(OrderDocument order, ExternalFnsResponse response) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.putAll(order.getPayload());
        mapped.put("registeringFns", response.registeringFns());
        mapped.put("registeringFnsName", response.registeringFnsName());
        mapped.put("externalStatus", response.externalStatus());
        mapped.put("riskScore", response.riskScore());
        mapped.put("mappingChecksum", checksum(mapped));
        mapped.put("clientType", order.getClientType());
        return mapped;
    }

    private long checksum(Map<String, Object> payload) {
        long checksum = 17;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            checksum = checksum * 31 + entry.getKey().hashCode();
            if (entry.getValue() != null) {
                checksum = checksum * 31 + entry.getValue().toString().hashCode();
            }
        }
        return checksum;
    }
}
