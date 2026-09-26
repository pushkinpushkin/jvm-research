package dev.pushkin.jvmresearch.enterprise.external;

public class ExpectedExternalFault extends RuntimeException {
    public ExpectedExternalFault(ExternalMode mode, RuntimeException cause) {
        super(mode.name(), cause);
    }
}
