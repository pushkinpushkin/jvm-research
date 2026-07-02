package dev.pushkin.jvmresearch.enterprise.domain;

public class GoskeyProcess {

    private String status;
    private String failMessage;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailMessage() {
        return failMessage;
    }

    public void setFailMessage(String failMessage) {
        this.failMessage = failMessage;
    }
}
