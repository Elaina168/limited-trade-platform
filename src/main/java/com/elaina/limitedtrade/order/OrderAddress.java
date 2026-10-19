package com.elaina.limitedtrade.order;

public record OrderAddress(Long orderId, Long sourceAddressId, String recipientName, String phone,
                           String province, String city, String district, String detailAddress) {
}
