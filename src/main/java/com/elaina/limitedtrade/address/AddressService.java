package com.elaina.limitedtrade.address;

import java.util.List;
import com.elaina.limitedtrade.user.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AddressService {
    private final AddressMapper mapper;
    private final AppUserMapper users;

    public AddressService(AddressMapper mapper, AppUserMapper users) {
        this.mapper = mapper;
        this.users = users;
    }

    public List<AddressResponse> listAddresses(long userId) {
        return mapper.findUserAddresses(userId).stream().map(AddressResponse::from).toList();
    }

    @Transactional
    public AddressResponse createAddress(long userId, SaveAddressRequest request) {
        users.lockById(userId);
        UserAddress address = addressDetails(userId, request);
        // 所有写操作共用用户行锁，避免并发首次新增产生多个默认项。
        address.setDefaultAddress(mapper.countUserAddresses(userId) == 0);
        mapper.insertAddress(address);
        return AddressResponse.from(address);
    }

    @Transactional
    public AddressResponse updateAddress(long userId, long id, SaveAddressRequest request) {
        users.lockById(userId);
        UserAddress address = addressDetails(userId, request);
        address.setId(id);
        if (mapper.updateDetails(address) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "地址不存在");
        }
        return AddressResponse.from(mapper.findOwnedAddress(id, userId));
    }

    @Transactional
    public void deleteAddress(long userId, long id) {
        users.lockById(userId);
        if (mapper.deleteOwnedAddress(id, userId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "地址不存在");
        }
    }

    @Transactional
    public void setDefaultAddress(long userId, long id) {
        users.lockById(userId);
        // 先确认本人目标，再清旧设新；任何SQL失败都由同一事务回滚。
        if (mapper.findOwnedAddress(id, userId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "地址不存在");
        }
        mapper.clearDefault(userId);
        mapper.markDefault(id, userId);
    }

    private UserAddress addressDetails(long userId, SaveAddressRequest request) {
        UserAddress address = new UserAddress();
        address.setUserId(userId);
        address.setRecipientName(request.recipientName().strip());
        address.setPhone(request.phone());
        address.setProvince(request.province().strip());
        address.setCity(request.city().strip());
        address.setDistrict(request.district().strip());
        address.setDetailAddress(request.detailAddress().strip());
        return address;
    }
}
