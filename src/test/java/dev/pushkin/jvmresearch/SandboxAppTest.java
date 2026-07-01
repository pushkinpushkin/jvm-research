package dev.pushkin.jvmresearch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SandboxAppTest {

    @Test
    void shouldRunSandboxAppWithSmallPayload() {
        assertDoesNotThrow(() -> SandboxApp.main(new String[0]));
    }
}
