package dev.pushkin.jvmresearch.enterprise.service;

public record GenerateOrdersResponse(
        int requested,
        int saved
) {
}
