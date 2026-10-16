package com.elaina.limitedtrade.product;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {

    private final ProductMapper productMapper;

    public ProductService(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    public List<Product> listOnSaleProducts() {
        return productMapper.findOnSaleProducts();
    }

    @Transactional
    public Product createProduct(CreateProductRequest request) {
        Product product = new Product(null, request.name(), request.price(), request.stock(), "OFF_SALE");
        productMapper.insertProduct(product);
        return product;
    }

    @Transactional
    public Product updateProduct(long id, UpdateProductRequest request) {
        if (productMapper.updateDetails(id, request) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        return productMapper.findById(id);
    }

    @Transactional
    public Product updateProductStatus(long id, UpdateProductStatusRequest request) {
        if (productMapper.updateStatus(id, request.status()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        return productMapper.findById(id);
    }
}
