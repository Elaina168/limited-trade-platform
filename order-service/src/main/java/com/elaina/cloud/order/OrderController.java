package com.elaina.cloud.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestController
class OrderController {
    private final OrderService orders;
    private final OrderRepository repository;
    private final ProductAggregation aggregation;
    private final MarketingCircuit circuit;

    OrderController(OrderService orders, OrderRepository repository, ProductAggregation aggregation, MarketingCircuit circuit) {
        this.orders = orders;
        this.repository = repository;
        this.aggregation = aggregation;
        this.circuit = circuit;
    }

    @GetMapping("/api/aggregate/products/{id}")
    ProductAggregation.Detail product(@PathVariable @Positive long id,
        @RequestParam(defaultValue = "parallel") @Pattern(regexp = "parallel|serial") String mode) {
        return aggregation.detail(id, "serial".equals(mode));
    }

    @PostMapping("/api/orders")
    ResponseEntity<OrderRepository.Order> create(@RequestHeader("X-User-Id") @Positive long userId,
                                                @Valid @RequestBody OrderService.Request request) {
        var result = orders.create(userId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.order());
    }

    @GetMapping("/api/orders/{id}")
    OrderRepository.Order owned(@RequestHeader("X-User-Id") @Positive long userId, @PathVariable @Positive long id) {
        return orders.owned(userId, id);
    }

    @PostMapping("/api/orders/{id}/cancel")
    OrderRepository.Order cancel(@RequestHeader("X-User-Id") @Positive long userId, @PathVariable @Positive long id) {
        return orders.cancel(userId, id);
    }

    @GetMapping("/internal/orders/{id}/state")
    Map<String, String> state(@PathVariable @Positive long id) { return Map.of("status", repository.state(id)); }

    @GetMapping("/lab/circuit")
    Map<String, Map<String, Object>> circuits() { return circuit.status(); }
}

@RestControllerAdvice
class OrderErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> response(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason(), "traceId", MDC.get(TraceContextFilter.KEY)));
    }
}
