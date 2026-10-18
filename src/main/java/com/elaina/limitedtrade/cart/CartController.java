package com.elaina.limitedtrade.cart;

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

@RestController
@RequestMapping("/api/cart/items")
public class CartController {
    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    public List<CartItemResponse> listItems(@AuthenticationPrincipal PlatformUserDetails user) {
        return service.listItems(user.getId());
    }

    @PutMapping("/{productId}")
    public ResponseEntity<Void> setQuantity(@AuthenticationPrincipal PlatformUserDetails user,
                                            @PathVariable @Positive long productId,
                                            @Valid @RequestBody SetCartItemRequest request) {
        service.setQuantity(user.getId(), productId, request.quantity());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> removeItem(@AuthenticationPrincipal PlatformUserDetails user,
                                          @PathVariable @Positive long productId) {
        service.removeItem(user.getId(), productId);
        return ResponseEntity.noContent().build();
    }
}
