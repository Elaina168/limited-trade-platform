package com.elaina.limitedtrade.address;

import java.util.List;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/addresses")
public class AddressController {
    private final AddressService service;

    public AddressController(AddressService service) {
        this.service = service;
    }

    @GetMapping
    public List<AddressResponse> listAddresses(@AuthenticationPrincipal PlatformUserDetails user) {
        return service.listAddresses(user.getId());
    }

    @PostMapping
    public ResponseEntity<AddressResponse> createAddress(@AuthenticationPrincipal PlatformUserDetails user,
                                                         @Valid @RequestBody SaveAddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createAddress(user.getId(), request));
    }

    @PutMapping("/{id}")
    public AddressResponse updateAddress(@AuthenticationPrincipal PlatformUserDetails user,
                                         @PathVariable @Positive long id,
                                         @Valid @RequestBody SaveAddressRequest request) {
        return service.updateAddress(user.getId(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAddress(@AuthenticationPrincipal PlatformUserDetails user,
                                             @PathVariable @Positive long id) {
        service.deleteAddress(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/default")
    public ResponseEntity<Void> setDefaultAddress(@AuthenticationPrincipal PlatformUserDetails user,
                                                 @PathVariable @Positive long id) {
        service.setDefaultAddress(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
