package dev.pushkin.jvmresearch.enterprise.domain;

public enum GoskeyProcessStatus {
    DONE,
    PROCESSING,
    SIGN_FAILED,
    EXTERNAL_FAILED,
    UNKNOWN;

    public static GoskeyProcessStatus fromExternal(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return GoskeyProcessStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }

    public boolean isFinalSuccess() {
        return this == DONE;
    }

    public boolean isFinalFailure() {
        return name().endsWith("FAILED");
    }
}
