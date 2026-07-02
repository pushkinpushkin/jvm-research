package dev.pushkin.jvmresearch.enterprise.runinfo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RunInfoStartupLogger implements ApplicationRunner {

    private final RunInfoService service;

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
