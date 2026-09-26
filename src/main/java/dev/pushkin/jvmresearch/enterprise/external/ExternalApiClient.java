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
    private final dev.pushkin.jvmresearch.enterprise.service.ResearchMetrics metrics;

    public ExternalApiClient(
            RestClient.Builder builder,
            SandboxProperties properties,
            TrafficProfile trafficProfile,
            dev.pushkin.jvmresearch.enterprise.service.ResearchMetrics metrics) {
        this.restClient = builder.baseUrl(properties.external().baseUrl()).build();
        this.trafficProfile = trafficProfile;
        this.metrics = metrics;
    }

    public ExternalFnsResponse getFnsData(String orderId) {
        ExternalMode mode = trafficProfile.externalMode(orderId, "fns-data");
        log.info("External FNS request started orderId={} mode={}", orderId, mode);
        ExternalFnsResponse response =
                invoke(
                        mode,
                        () ->
                                restClient
                                        .get()
                                        .uri(
                                                uriBuilder ->
                                                        uriBuilder
                                                                .path(
                                                                        "/external/fns-data/{orderId}")
                                                                .queryParam(
                                                                        "mode",
                                                                        toWireMockMode(mode))
                                                                .build(orderId))
                                        .retrieve()
                                        .body(ExternalFnsResponse.class));
        log.info(
                "External FNS request finished orderId={} mode={} status={}",
                orderId,
                mode,
                response == null ? "n/a" : response.externalStatus());
        return response;
    }

    public ExternalSignStatusResponse getSignStatus(String requestId) {
        ExternalMode mode = trafficProfile.externalMode(requestId, "external-status");
        log.info("External sign status request started requestId={} mode={}", requestId, mode);
        ExternalSignStatusResponse response =
                invoke(
                        mode,
                        () ->
                                restClient
                                        .get()
                                        .uri(
                                                uriBuilder ->
                                                        uriBuilder
                                                                .path(
                                                                        "/external/process/status/{requestId}")
                                                                .queryParam(
                                                                        "mode",
                                                                        toWireMockMode(mode))
                                                                .build(requestId))
                                        .retrieve()
                                        .body(ExternalSignStatusResponse.class));
        log.info(
                "External sign status request finished requestId={} mode={} status={}",
                requestId,
                mode,
                response == null ? "n/a" : response.status());
        return response;
    }

    private <T> T invoke(ExternalMode mode, java.util.function.Supplier<T> call) {
        metrics.increment("external_" + mode.name().toLowerCase(java.util.Locale.ROOT));
        try {
            return call.get();
        } catch (RuntimeException ex) {
            // Classify the observed failure, not merely the configured mode.
            boolean expected =
                    mode == ExternalMode.ERROR
                                    && ex
                                            instanceof
                                            org.springframework.web.client.HttpServerErrorException
                                                            h
                                    && h.getStatusCode().value() == 503
                            || mode == ExternalMode.LONG_DELAY
                                    && hasCause(ex, java.net.SocketTimeoutException.class)
                            || mode == ExternalMode.BAD_RESPONSE
                                    && hasCause(
                                            ex,
                                            org.springframework.http.converter
                                                    .HttpMessageNotReadableException.class);
            if (expected) throw new ExpectedExternalFault(mode, ex);
            throw ex;
        }
    }

    private boolean hasCause(Throwable ex, Class<?> type) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause())
            if (type.isInstance(cause)) return true;
        return false;
    }

    private String toWireMockMode(ExternalMode mode) {
        return mode.name().toLowerCase().replace('-', '_');
    }
}
