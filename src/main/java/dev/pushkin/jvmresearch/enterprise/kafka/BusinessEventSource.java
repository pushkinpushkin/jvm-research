package dev.pushkin.jvmresearch.enterprise.kafka;

public enum BusinessEventSource {
    GENERATOR("generator"),
    HTTP("http"),
    EXTERNAL_API("external-api"),
    HTTP_PROCESS("http-process"),
    HTTP_PROCESS_FAILED("http-process-failed"),
    ORDER_STATUS_CONSUMER("order-status-consumer"),
    BUSINESS_EVENT_CONSUMER("business-event-consumer"),
    STATUS_POLLING_SCHEDULER("status-polling-scheduler"),
    RETRY_SCHEDULER("retry-scheduler");

    private final String value;

    BusinessEventSource(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
