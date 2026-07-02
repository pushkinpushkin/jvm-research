package dev.pushkin.jvmresearch.enterprise.domain;

public enum FnsProcessStatus {
    FNS_OK,
    FNS_FAILED,
    UNKNOWN;

    public static FnsProcessStatus fromExternal(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return FnsProcessStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }

    public boolean isSuccessful() {
        return this == FNS_OK;
    }
}
