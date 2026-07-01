package dev.pushkin.jvmresearch.enterprise.web;

import dev.pushkin.jvmresearch.enterprise.domain.OrderDocument;
import dev.pushkin.jvmresearch.enterprise.repository.OrderRepository;
import dev.pushkin.jvmresearch.enterprise.service.EnterpriseOrderFlowService;
import dev.pushkin.jvmresearch.enterprise.service.GenerateOrdersResponse;
import dev.pushkin.jvmresearch.enterprise.service.OrderGeneratorService;
import dev.pushkin.jvmresearch.enterprise.service.ProcessOrderResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderFlowController {

    private final EnterpriseOrderFlowService flowService;
    private final OrderGeneratorService generatorService;
    private final OrderRepository repository;

    public OrderFlowController(
            EnterpriseOrderFlowService flowService,
            OrderGeneratorService generatorService,
            OrderRepository repository
    ) {
        this.flowService = flowService;
        this.generatorService = generatorService;
        this.repository = repository;
    }

    @PostMapping("/{orderId}/process")
    public ProcessOrderResponse process(@PathVariable String orderId) {
        return flowService.process(orderId);
    }

    @PostMapping("/generate")
    public GenerateOrdersResponse generate(@RequestParam(defaultValue = "10000") int count) {
        return generatorService.generate(count);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDocument> get(@PathVariable String orderId) {
        return repository.findById(orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
