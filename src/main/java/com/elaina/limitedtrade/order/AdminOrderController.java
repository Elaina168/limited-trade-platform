package com.elaina.limitedtrade.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
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
            @Pattern(regexp = "PENDING_PAYMENT|PAID|CANCELED|CLOSED") String status,
            @RequestParam(required = false) @Positive Long userId) {
        return orderService.listAdminOrders(page, size, status, userId);
    }
}
