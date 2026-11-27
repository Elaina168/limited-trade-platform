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
        requireOnSale(productMapper.findById(productId));
        // 保存意向数量，不预留库存；同键重复请求覆盖数量，不累加。
        cartMapper.setQuantity(userId, productId, quantity);
    }

    @Transactional
    public CartItemResponse addItem(long userId, long productId, int delta) {
        // 与购物车修改和结算共用用户锁，并以事务中的最新商品库存判断上限。
        users.lockById(userId);
        Product product = requireOnSale(productMapper.findByIdForUpdate(productId));
        int stock = product.getStock();
        if (stock <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品已售罄");
        }
        Integer existingQty = cartMapper.findQuantity(userId, productId);
        long newQty = (existingQty == null ? 0L : existingQty.longValue()) + delta;
        if (newQty > stock) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "购物车数量已超出可购库存");
        }
        int quantity = (int) newQty;
        cartMapper.setQuantity(userId, productId, quantity);
        return new CartItemResponse(productId, product.getName(), product.getPrice(), stock, product.getStatus(), quantity);
    }

    @Transactional
    public void removeItem(long userId, long productId) {
        users.lockById(userId);
        cartMapper.deleteItem(userId, productId);
    }

    private Product requireOnSale(Product product) {
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        if (!"ON_SALE".equals(product.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品已下架");
        }
        return product;
    }
}
