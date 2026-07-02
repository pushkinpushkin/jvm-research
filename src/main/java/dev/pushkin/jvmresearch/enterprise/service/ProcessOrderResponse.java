package dev.pushkin.jvmresearch.enterprise.service;

public record ProcessOrderResponse(
        String orderId,
        String status,
        String message,
        long version
) {
}
