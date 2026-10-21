package com.elaina.cloud.preview;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
public class PreviewController {
    private final PreviewService service;
    public PreviewController(PreviewService service){this.service=service;}
    @PostMapping("/api/previews") public PreviewService.Preview preview(@Valid @RequestBody Request request){
        return service.preview(request.productId(),request.quantity(),MDC.get("traceId"));
    }
    public record Request(@Positive long productId,@Min(1) @Max(10) int quantity){}
}
@Profile("drill")
@RestController
class CircuitStatusController {
    private final CircuitBreaker circuit;
    CircuitStatusController(CircuitBreaker circuit){this.circuit=circuit;}
    @GetMapping("/lab/circuit") public java.util.Map<String,Object> state(){
        return java.util.Map.of("state",circuit.getState().name(),"failedCalls",circuit.getMetrics().getNumberOfFailedCalls());
    }
}
