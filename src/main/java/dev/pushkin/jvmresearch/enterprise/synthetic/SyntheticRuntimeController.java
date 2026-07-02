package dev.pushkin.jvmresearch.enterprise.synthetic;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/synthetic")
public class SyntheticRuntimeController {

    private final SyntheticRuntimeService service;

    public SyntheticRuntimeController(SyntheticRuntimeService service) {
        this.service = service;
    }

    @PostMapping("/runtime")
    public SyntheticRuntimeResponse run(
            @RequestParam(defaultValue = "20") int iterations,
            @RequestParam(defaultValue = "100000") int payloadSize
    ) {
        return service.run(iterations, payloadSize);
    }
}
