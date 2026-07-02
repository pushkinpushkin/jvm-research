package dev.pushkin.jvmresearch.enterprise.domain;

import java.time.Instant;

public class StatusHistoryItem {

    private String status;
    private String source;
    private String message;
    private Instant occurredAt;

    public StatusHistoryItem() {
    }

    public StatusHistoryItem(String status, String source, String message, Instant occurredAt) {
        this.status = status;
        this.source = source;
        this.message = message;
        this.occurredAt = occurredAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
