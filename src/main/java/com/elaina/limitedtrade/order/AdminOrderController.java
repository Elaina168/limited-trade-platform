package com.elaina.limitedtrade.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public AdminOrderPageResponse listOrders(
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size,
            @RequestParam(required = false)
            @Pattern(regexp = "PENDING_PAYMENT|PAID|CANCELED|CLOSED|SHIPPED|COMPLETED") String status,
            @RequestParam(required = false) @Positive Long userId) {
        return orderService.listAdminOrders(page, size, status, userId);
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable @Positive long id) {
        return OrderResponse.from(orderService.getAdminOrder(id));
    }

    @PostMapping("/{id}/ship")
    public OrderResponse shipOrder(@PathVariable @Positive long id,
                                    @Valid @RequestBody ShipOrderRequest request) {
        return OrderResponse.from(orderService.shipOrder(id, request));
    }
}
