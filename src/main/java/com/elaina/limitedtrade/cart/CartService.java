package com.elaina.limitedtrade.cart;

import java.util.List;
import com.elaina.limitedtrade.product.Product;
import com.elaina.limitedtrade.product.ProductMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CartService {
    private final CartItemMapper cartMapper;
    private final ProductMapper productMapper;

    public CartService(CartItemMapper cartMapper, ProductMapper productMapper) {
        this.cartMapper = cartMapper;
        this.productMapper = productMapper;
    }

    public List<CartItemResponse> listItems(long userId) {
        return cartMapper.findUserItems(userId);
    }

    @Transactional
    public void setQuantity(long userId, long productId, int quantity) {
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
        cartMapper.deleteItem(userId, productId);
    }
}
