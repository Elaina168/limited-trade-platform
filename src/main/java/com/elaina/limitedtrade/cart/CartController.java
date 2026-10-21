package com.elaina.limitedtrade.cart;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "购物车")
@RestController
@RequestMapping("/api/cart/items")
public class CartController {
    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "查询本人购物车", description = "仅返回当前登录用户的购物车。")
    public List<CartItemResponse> listItems(@AuthenticationPrincipal PlatformUserDetails user) {
        return service.listItems(user.getId());
    }

    @PutMapping("/{productId}")
    @Operation(summary = "设置购物车商品数量", description = "数量表示最终数量；商品不存在返回404，下架返回409；成功返回204。")
    public ResponseEntity<Void> setQuantity(@AuthenticationPrincipal PlatformUserDetails user,
                                            @PathVariable @Positive long productId,
                                            @Valid @RequestBody SetCartItemRequest request) {
        service.setQuantity(user.getId(), productId, request.quantity());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{productId}")
    @Operation(summary = "移除购物车商品", description = "仅移除本人购物车中的商品，成功返回204。")
    public ResponseEntity<Void> removeItem(@AuthenticationPrincipal PlatformUserDetails user,
                                          @PathVariable @Positive long productId) {
        service.removeItem(user.getId(), productId);
        return ResponseEntity.noContent().build();
    }
}
