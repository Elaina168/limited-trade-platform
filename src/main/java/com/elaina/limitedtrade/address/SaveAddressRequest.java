package com.elaina.limitedtrade.address;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SaveAddressRequest(
        @NotBlank @Size(max = 50) String recipientName,
        @NotBlank @Pattern(regexp = "1[3-9][0-9]{9}") String phone,
        @NotBlank @Size(max = 50) String province,
        @NotBlank @Size(max = 50) String city,
        @NotBlank @Size(max = 50) String district,
        @NotBlank @Size(max = 200) String detailAddress) {
}
