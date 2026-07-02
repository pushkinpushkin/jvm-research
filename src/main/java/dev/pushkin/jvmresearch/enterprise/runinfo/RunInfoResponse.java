package dev.pushkin.jvmresearch.enterprise.runinfo;

import java.util.List;
import java.util.Map;

public record RunInfoResponse(
        String runId,
        String runProfile,
        String jvmVariant,
        String jvmProfile,
        String gitSha,
        String startedAt,
        String javaVersion,
        String javaVendor,
        String vmName,
        String vmVersion,
        String vmVendor,
        List<String> inputArguments,
        long maxHeapMb,
        long totalHeapMb,
        long freeHeapMb,
        int availableProcessors,
        Map<String, String> runtimeEnvironment,
        Map<String, String> workHelmReference
) {
}
