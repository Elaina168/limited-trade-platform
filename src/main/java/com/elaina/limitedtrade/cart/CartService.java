package com.elaina.limitedtrade.cart;

import java.util.List;
import com.elaina.limitedtrade.product.Product;
import com.elaina.limitedtrade.product.ProductMapper;
import com.elaina.limitedtrade.user.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CartService {
    private final CartItemMapper cartMapper;
    private final ProductMapper productMapper;
    private final AppUserMapper users;

    public CartService(CartItemMapper cartMapper, ProductMapper productMapper, AppUserMapper users) {
        this.cartMapper = cartMapper;
        this.productMapper = productMapper;
        this.users = users;
    }

    public List<CartItemResponse> listItems(long userId) {
        return cartMapper.findUserItems(userId);
    }

    @Transactional
    public void setQuantity(long userId, long productId, int quantity) {
        // 与结算共用用户锁，结算完成后的新购买意向不会被本次结算删除。
        users.lockById(userId);
        Product product = productMapper.findById(productId);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        if (!"ON_SALE".equals(product.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品已下架");
        }
        // 保存意向数量，不预留库存；同键重复请求覆盖数量，不累加。
        cartMapper.setQuantity(userId, productId, quantity);
    }

    @Transactional
    public void removeItem(long userId, long productId) {
        users.lockById(userId);
        cartMapper.deleteItem(userId, productId);
    }
}
