package dev.pushkin.jvmresearch.enterprise.runinfo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RunInfoController {

    private final RunInfoService service;

    public RunInfoController(RunInfoService service) {
        this.service = service;
    }

    @GetMapping("/run-info")
    public RunInfoResponse getRunInfo() {
        return service.getRunInfo();
    }
}
