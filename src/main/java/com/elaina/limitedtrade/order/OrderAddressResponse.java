package com.elaina.limitedtrade.order;

public record OrderAddressResponse(String recipientName, String phone, String province,
                                   String city, String district, String detailAddress) {
    public static OrderAddressResponse from(OrderAddress address) {
        return new OrderAddressResponse(address.recipientName(), address.phone(), address.province(),
                address.city(), address.district(), address.detailAddress());
    }
}
