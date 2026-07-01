package dev.pushkin.jvmresearch.enterprise.domain;

public class AucProcess {

    private String status;
    private String ecpSerialNumber;
    private String failMessage;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEcpSerialNumber() {
        return ecpSerialNumber;
    }

    public void setEcpSerialNumber(String ecpSerialNumber) {
        this.ecpSerialNumber = ecpSerialNumber;
    }

    public String getFailMessage() {
        return failMessage;
    }

    public void setFailMessage(String failMessage) {
        this.failMessage = failMessage;
    }
}
