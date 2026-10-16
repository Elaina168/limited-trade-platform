package com.elaina.limitedtrade.activity;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateActivityRequest(
        @NotNull @Positive Long productId,
        @NotBlank @Size(max = 100) String name,
        @NotNull @Positive Integer totalQuantity,
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt) {
}
