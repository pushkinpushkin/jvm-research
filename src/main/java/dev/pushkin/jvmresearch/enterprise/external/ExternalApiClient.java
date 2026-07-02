package dev.pushkin.jvmresearch.enterprise.external;

import dev.pushkin.jvmresearch.enterprise.config.SandboxProperties;
import dev.pushkin.jvmresearch.enterprise.service.TrafficProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
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
        log.info("External FNS request started orderId={} mode={}", orderId, mode);
        ExternalFnsResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/external/fns-data/{orderId}")
                        .queryParam("mode", toWireMockMode(mode))
                        .build(orderId))
                .retrieve()
                .body(ExternalFnsResponse.class);
        log.info("External FNS request finished orderId={} mode={} status={}", orderId, mode, response == null ? "n/a" : response.externalStatus());
        return response;
    }

    public ExternalSignStatusResponse getSignStatus(String requestId) {
        ExternalMode mode = trafficProfile.externalMode(requestId, "external-status");
        log.info("External sign status request started requestId={} mode={}", requestId, mode);
        ExternalSignStatusResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/external/process/status/{requestId}")
                        .queryParam("mode", toWireMockMode(mode))
                        .build(requestId))
                .retrieve()
                .body(ExternalSignStatusResponse.class);
        log.info("External sign status request finished requestId={} mode={} status={}", requestId, mode, response == null ? "n/a" : response.status());
        return response;
    }

    private String toWireMockMode(ExternalMode mode) {
        return mode.name().toLowerCase().replace('-', '_');
    }
}
