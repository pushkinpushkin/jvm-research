package dev.pushkin.jvmresearch.enterprise.domain;

public class FnsProcess {

    private String status;
    private String registeringFns;
    private String registeringFnsName;
    private String failMessage;
    private int attempts;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRegisteringFns() {
        return registeringFns;
    }

    public void setRegisteringFns(String registeringFns) {
        this.registeringFns = registeringFns;
    }

    public String getRegisteringFnsName() {
        return registeringFnsName;
    }

    public void setRegisteringFnsName(String registeringFnsName) {
        this.registeringFnsName = registeringFnsName;
    }

    public String getFailMessage() {
        return failMessage;
    }

    public void setFailMessage(String failMessage) {
        this.failMessage = failMessage;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }
}
