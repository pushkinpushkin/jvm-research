package dev.pushkin.jvmresearch.enterprise.runinfo;

import dev.pushkin.jvmresearch.enterprise.config.ExperimentRunProperties;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class RunInfoService {

    private final ExperimentRunProperties properties;
    private final Environment environment;
    private final Instant startedAt = Instant.now();

    public RunInfoService(ExperimentRunProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    public RunInfoResponse getRunInfo() {
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        Runtime runtime = Runtime.getRuntime();

        return new RunInfoResponse(
                properties.id(),
                properties.profile(),
                properties.jvmVariant(),
                properties.jvmProfile(),
                properties.gitSha(),
                startedAt.toString(),
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("java.vm.name"),
                System.getProperty("java.vm.version"),
                System.getProperty("java.vm.vendor"),
                runtimeMxBean.getInputArguments(),
                toMb(runtime.maxMemory()),
                toMb(runtime.totalMemory()),
                toMb(runtime.freeMemory()),
                runtime.availableProcessors(),
                runtimeEnvironment(),
                workHelmReference()
        );
    }

    private Map<String, String> runtimeEnvironment() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("serverPort", environment.getProperty("server.port", "n/a"));
        values.put("springProfilesActive", String.join(",", environment.getActiveProfiles()));
        values.put("timezone", System.getProperty("user.timezone", "n/a"));
        values.put("containerCpuLimit", properties.containerCpuLimit());
        values.put("containerMemoryLimit", properties.containerMemoryLimit());
        return values;
    }

    private Map<String, String> workHelmReference() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("replicaCount", "3");
        values.put("cpuRequest", properties.workCpuRequest());
        values.put("cpuLimit", properties.workCpuLimit());
        values.put("memoryRequest", properties.workMemoryRequest());
        values.put("memoryLimit", properties.workMemoryLimit());
        values.put("timezone", properties.workTimezone());
        values.put("port", "8080");
        values.put("prometheusEnabled", "true");
        values.put("extraJavaOpts", properties.workExtraJavaOpts());
        return values;
    }

    private long toMb(long bytes) {
        return bytes / 1024 / 1024;
    }
}
