package com.elaina.cloud.stock;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "order-service")
interface OrderStateClient {
    @GetMapping("/internal/orders/{orderId}/state")
    State state(@PathVariable("orderId") long orderId);

    record State(String status) {}
}
