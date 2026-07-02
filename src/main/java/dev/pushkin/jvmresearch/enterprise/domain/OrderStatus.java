package dev.pushkin.jvmresearch.enterprise.domain;

public enum OrderStatus {
    NEW,
    PROCESSING,
    WAITING_EXTERNAL_STATUS,
    COMPLETED,
    FAILED
}
