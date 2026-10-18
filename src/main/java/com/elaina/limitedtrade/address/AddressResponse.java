package com.elaina.limitedtrade.address;

public record AddressResponse(Long id, String recipientName, String phone, String province,
                              String city, String district, String detailAddress, boolean defaultAddress) {
    public static AddressResponse from(UserAddress address) {
        return new AddressResponse(address.getId(), address.getRecipientName(), address.getPhone(),
                address.getProvince(), address.getCity(), address.getDistrict(), address.getDetailAddress(),
                address.isDefaultAddress());
    }
}
