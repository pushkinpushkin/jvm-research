package dev.pushkin.jvmresearch.enterprise.external;

public record ExternalSignStatusResponse(
        String requestId,
        String status,
        String failMessage
) {
}
