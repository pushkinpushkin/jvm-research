package dev.pushkin.jvmresearch.enterprise.external;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.service.TrafficProfile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ExternalApiClient {

    private final RestClient restClient;
    private final TrafficProfile trafficProfile;

    public ExternalApiClient(RestClient.Builder builder, SandboxProperties properties, TrafficProfile trafficProfile) {
        this.restClient = builder.baseUrl(properties.external().baseUrl()).build();
        this.trafficProfile = trafficProfile;
    }

    public ExternalFnsResponse getFnsData(String orderId) {
        ExternalMode mode = trafficProfile.externalMode(orderId, "fns-data");
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/external/fns-data/{orderId}")
                        .queryParam("mode", toWireMockMode(mode))
                        .build(orderId))
                .retrieve()
                .body(ExternalFnsResponse.class);
    }

    public ExternalSignStatusResponse getSignStatus(String requestId) {
        ExternalMode mode = trafficProfile.externalMode(requestId, "external-status");
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/external/process/status/{requestId}")
                        .queryParam("mode", toWireMockMode(mode))
                        .build(requestId))
                .retrieve()
                .body(ExternalSignStatusResponse.class);
    }

    private String toWireMockMode(ExternalMode mode) {
        return mode.name().toLowerCase().replace('-', '_');
    }
}
