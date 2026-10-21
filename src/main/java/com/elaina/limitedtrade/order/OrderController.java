package com.elaina.limitedtrade.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.ratelimit.RedisOrderRateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "本人订单")
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final RedisOrderRateLimiter rateLimiter;

    public OrderController(OrderService orderService, RedisOrderRateLimiter rateLimiter) {
        this.orderService = orderService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    @Operation(summary = "创建单商品订单", description = "首次创建返回201，相同requestId及购买参数重试返回200；同一requestId改动购买参数返回409。可选activityId、userCouponId、addressId。")
    public ResponseEntity<OrderResponse> createOrder(@AuthenticationPrincipal PlatformUserDetails user,
                                                     @Valid @RequestBody CreateOrderRequest request) {
        rateLimiter.check(user.getId());
        CreateOrderResult result = orderService.createOrder(user.getId(), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(OrderResponse.from(result.order()));
    }

    @GetMapping
    @Operation(summary = "分页查询本人订单", description = "page默认1，size默认20且最多100，可按status筛选；只查本人，按订单编号倒序。超出末页返回空items并保留total。")
    public OrderPageResponse listOrders(@AuthenticationPrincipal PlatformUserDetails user,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size,
            @Parameter(description = "可选订单状态，省略时查询全部状态", schema = @Schema(allowableValues = {
                    "PENDING_PAYMENT", "PAID", "CANCELED", "CLOSED", "SHIPPED", "COMPLETED"}))
            @RequestParam(required = false)
            @Pattern(regexp = "PENDING_PAYMENT|PAID|CANCELED|CLOSED|SHIPPED|COMPLETED") String status) {
        return orderService.listUserOrders(user.getId(), page, size, status);
    }

    @PostMapping("/checkout")
    @Operation(summary = "结算购物车选中商品", description = "使用productIds、本人addressId及requestId；首次201，相同请求重试200，购买参数冲突409。")
    public ResponseEntity<OrderResponse> checkout(@AuthenticationPrincipal PlatformUserDetails user,
                                                  @Valid @RequestBody CheckoutRequest request) {
        rateLimiter.check(user.getId());
        CreateOrderResult result = orderService.checkout(user.getId(), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(OrderResponse.from(result.order()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询本人订单详情", description = "订单不存在或属于其他用户均返回404。")
    public OrderResponse getOrder(@AuthenticationPrincipal PlatformUserDetails user,
                                   @PathVariable @Positive long id) {
        return OrderResponse.from(orderService.getUserOrder(user.getId(), id));
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "模拟支付本人订单", description = "待支付且未过期才可支付；已支付重复请求返回原结果，其他状态冲突409。")
    public OrderResponse payOrder(@AuthenticationPrincipal PlatformUserDetails user,
                                   @PathVariable @Positive long id) {
        return OrderResponse.from(orderService.payOrder(user.getId(), id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消本人订单", description = "取消待支付订单并归还库存；已取消重复请求返回原结果，其他状态冲突409。")
    public OrderResponse cancelOrder(@AuthenticationPrincipal PlatformUserDetails user,
                                      @PathVariable @Positive long id) {
        return OrderResponse.from(orderService.cancelOrder(user.getId(), id));
    }

    @PostMapping("/{id}/receive")
    @Operation(summary = "确认收货", description = "本人已发货订单转为已完成；已完成重复请求返回原结果，其他状态冲突409。")
    public OrderResponse receiveOrder(@AuthenticationPrincipal PlatformUserDetails user,
                                       @PathVariable @Positive long id) {
        return OrderResponse.from(orderService.receiveOrder(user.getId(), id));
    }
}
