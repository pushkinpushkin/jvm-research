package dev.pushkin.jvmresearch.enterprise.kafka;

public final class StatusEvents {

    public static final String ORDER_STATUS_CHANGED = "ORDER_STATUS_CHANGED";
    public static final String BUSINESS_EVENT_ACCOUNT_FULLY_OPENED = "ACCOUNT_FULLY_OPENED";

    public static final String SOURCE_ORDER_STATUS_CONSUMER = "order-status-consumer";
    public static final String SOURCE_BUSINESS_EVENT_CONSUMER = "business-event-consumer";

    private StatusEvents() {
    }
}
