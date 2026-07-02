package dev.pushkin.jvmresearch.enterprise.runinfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class RunInfoStartupLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RunInfoStartupLogger.class);

    private final RunInfoService service;

    public RunInfoStartupLogger(RunInfoService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        RunInfoResponse runInfo = service.getRunInfo();
        log.info(
                "JVM experiment run started: runId={}, runProfile={}, jvmVariant={}, jvmProfile={}, gitSha={}, vmName={}, javaVersion={}, maxHeapMb={}, processors={}, inputArguments={}",
                runInfo.runId(),
                runInfo.runProfile(),
                runInfo.jvmVariant(),
                runInfo.jvmProfile(),
                runInfo.gitSha(),
                runInfo.vmName(),
                runInfo.javaVersion(),
                runInfo.maxHeapMb(),
                runInfo.availableProcessors(),
                runInfo.inputArguments()
        );
    }
}
