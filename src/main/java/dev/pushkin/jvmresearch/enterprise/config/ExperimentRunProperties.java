package dev.pushkin.jvmresearch.enterprise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "experiment.run")
public record ExperimentRunProperties(
        String id,
        String profile,
        String jvmVariant,
        String jvmProfile,
        String gitSha,
        String containerCpuLimit,
        String containerMemoryLimit,
        String workCpuRequest,
        String workCpuLimit,
        String workMemoryRequest,
        String workMemoryLimit,
        String workTimezone,
        String workExtraJavaOpts
) {
}
