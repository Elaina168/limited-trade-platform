package com.elaina.limitedtrade.address;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "收货地址")
@RestController
@RequestMapping("/api/addresses")
public class AddressController {
    private final AddressService service;

    public AddressController(AddressService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "查询本人地址簿", description = "仅返回当前登录用户的地址。")
    public List<AddressResponse> listAddresses(@AuthenticationPrincipal PlatformUserDetails user) {
        return service.listAddresses(user.getId());
    }

    @PostMapping
    @Operation(summary = "新增本人地址", description = "首个地址自动成为默认地址；成功返回201。")
    public ResponseEntity<AddressResponse> createAddress(@AuthenticationPrincipal PlatformUserDetails user,
                                                         @Valid @RequestBody SaveAddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createAddress(user.getId(), request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改本人地址", description = "不存在或属于其他用户的地址均返回404。")
    public AddressResponse updateAddress(@AuthenticationPrincipal PlatformUserDetails user,
                                         @PathVariable @Positive long id,
                                         @Valid @RequestBody SaveAddressRequest request) {
        return service.updateAddress(user.getId(), id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除本人地址", description = "成功返回204；历史订单地址快照不受影响。")
    public ResponseEntity<Void> deleteAddress(@AuthenticationPrincipal PlatformUserDetails user,
                                             @PathVariable @Positive long id) {
        service.deleteAddress(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/default")
    @Operation(summary = "设置默认地址", description = "仅操作本人地址；成功返回204。")
    public ResponseEntity<Void> setDefaultAddress(@AuthenticationPrincipal PlatformUserDetails user,
                                                 @PathVariable @Positive long id) {
        service.setDefaultAddress(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
