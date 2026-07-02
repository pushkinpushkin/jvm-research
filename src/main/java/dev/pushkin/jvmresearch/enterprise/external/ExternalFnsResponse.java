package dev.pushkin.jvmresearch.enterprise.external;

public record ExternalFnsResponse(
        String orderId,
        String registeringFns,
        String registeringFnsName,
        String externalStatus,
        String riskScore
) {
}
