package dev.pushkin.jvmresearch.enterprise.external;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.service.ChaosPolicy;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ExternalApiClient {

    private final RestClient restClient;
    private final ChaosPolicy chaosPolicy;

    public ExternalApiClient(RestClient.Builder builder, SandboxProperties properties, ChaosPolicy chaosPolicy) {
        this.restClient = builder.baseUrl(properties.external().baseUrl()).build();
        this.chaosPolicy = chaosPolicy;
    }

    public ExternalFnsResponse getFnsData(String orderId) {
        ExternalMode mode = chaosPolicy.externalMode(orderId, "fns-data");
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/external/fns-data/{orderId}")
                        .queryParam("mode", mode.name().toLowerCase())
                        .build(orderId))
                .retrieve()
                .body(ExternalFnsResponse.class);
    }

    public ExternalSignStatusResponse getSignStatus(String requestId) {
        ExternalMode mode = chaosPolicy.externalMode(requestId, "sign-status");
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/external/sign/status/{requestId}")
                        .queryParam("mode", mode.name().toLowerCase())
                        .build(requestId))
                .retrieve()
                .body(ExternalSignStatusResponse.class);
    }
}
