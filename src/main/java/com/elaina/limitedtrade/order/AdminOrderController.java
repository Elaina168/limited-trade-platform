package com.elaina.limitedtrade.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "订单管理")
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @Operation(summary = "分页查询全部用户订单", description = "page默认1，size默认20且最多100；可按status、userId筛选，按订单编号倒序。")
    public AdminOrderPageResponse listOrders(
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size,
            @Parameter(description = "可选订单状态，省略时查询全部状态", schema = @Schema(allowableValues = {
                    "PENDING_PAYMENT", "PAID", "CANCELED", "CLOSED", "SHIPPED", "COMPLETED"}))
            @RequestParam(required = false)
            @Pattern(regexp = "PENDING_PAYMENT|PAID|CANCELED|CLOSED|SHIPPED|COMPLETED") String status,
            @RequestParam(required = false) @Positive Long userId) {
        return orderService.listAdminOrders(page, size, status, userId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询订单详情（管理员）", description = "可查询任意用户的订单；不存在返回404。")
    public OrderResponse getOrder(@PathVariable @Positive long id) {
        return OrderResponse.from(orderService.getAdminOrder(id));
    }

    @PostMapping("/{id}/ship")
    @Operation(summary = "登记订单发货", description = "仅已支付且有地址快照的订单可发货；相同物流信息重复提交返回已有结果，冲突返回409。")
    public OrderResponse shipOrder(@PathVariable @Positive long id,
                                    @Valid @RequestBody ShipOrderRequest request) {
        return OrderResponse.from(orderService.shipOrder(id, request));
    }
}
