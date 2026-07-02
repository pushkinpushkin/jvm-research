package dev.pushkin.jvmresearch.enterprise.service;

import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.external.ExternalFnsResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DtoMapperService {

    public Map<String, Object> mergeExternalFnsData(OrderDocument order, ExternalFnsResponse response) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.putAll(order.getPayload());
        mapped.put("registeringFns", response.registeringFns());
        mapped.put("registeringFnsName", response.registeringFnsName());
        mapped.put("externalStatus", response.externalStatus());
        mapped.put("riskScore", response.riskScore());
        mapped.put("clientType", order.getClientType());
        mapped.put("payloadSize", mapped.size());
        return mapped;
    }
}
