package com.elaina.limitedtrade.order;

import java.util.List;

import com.elaina.limitedtrade.auth.PlatformUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@AuthenticationPrincipal PlatformUserDetails user,
                                                     @Valid @RequestBody CreateOrderRequest request) {
        CreateOrderResult result = orderService.createOrder(user.getId(), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(OrderResponse.from(result.order()));
    }

    @GetMapping
    public List<OrderResponse> listOrders(@AuthenticationPrincipal PlatformUserDetails user) {
        return orderService.listUserOrders(user.getId()).stream().map(OrderResponse::from).toList();
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@AuthenticationPrincipal PlatformUserDetails user,
                                   @PathVariable @Positive long id) {
        return OrderResponse.from(orderService.getUserOrder(user.getId(), id));
    }

    @PostMapping("/{id}/pay")
    public OrderResponse payOrder(@AuthenticationPrincipal PlatformUserDetails user,
                                   @PathVariable @Positive long id) {
        return OrderResponse.from(orderService.payOrder(user.getId(), id));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancelOrder(@AuthenticationPrincipal PlatformUserDetails user,
                                      @PathVariable @Positive long id) {
        return OrderResponse.from(orderService.cancelOrder(user.getId(), id));
    }
}
