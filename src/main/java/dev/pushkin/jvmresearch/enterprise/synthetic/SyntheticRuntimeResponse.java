package dev.pushkin.jvmresearch.enterprise.synthetic;

public record SyntheticRuntimeResponse(
        int iterations,
        int payloadSize,
        long uptimeMs,
        double p50Ms,
        double p95Ms,
        double maxMs,
        long heapUsedMb,
        long gcCount,
        long checksum
) {
}
