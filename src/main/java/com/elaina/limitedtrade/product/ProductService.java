package com.elaina.limitedtrade.product;

import java.util.List;
import com.elaina.limitedtrade.category.CategoryMapper;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {

    private final ProductMapper productMapper;
    private final ProductCache productCache;
    private final CategoryMapper categoryMapper;

    public ProductService(ProductMapper productMapper, ProductCache productCache, CategoryMapper categoryMapper) {
        this.productMapper = productMapper;
        this.productCache = productCache;
        this.categoryMapper = categoryMapper;
    }

    public List<Product> listOnSaleProducts() {
        return productCache.getOrLoad(productMapper::findOnSaleProducts);
    }

    public Product getOnSaleProduct(long id) {
        Product product = productCache.findProduct(id, () -> productMapper.findById(id));
        if (product == null || !"ON_SALE".equals(product.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在或当前已下架");
        }
        return product;
    }

    public List<Product> listAdminProducts() {
        return productMapper.findAllProducts();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminProductPage pageAdminProducts(int page, int size, String keyword, Long categoryId, String status) {
        String literalKeyword = literalSearchKeyword(keyword);
        long offset = (long) (page - 1) * size;
        long total = productMapper.countAdminProducts(categoryId, literalKeyword, status);
        List<Product> items = productMapper.pageAdminProducts(categoryId, literalKeyword, status, offset, size);
        return new AdminProductPage(page, size, total, items);
    }

    public Product getAdminProduct(long id) {
        Product product = productMapper.findById(id);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        return product;
    }

    @Transactional
    public Product createProduct(CreateProductRequest request) {
        if (request.categoryId() != null && categoryMapper.findById(request.categoryId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分类不存在");
        }
        Product product = new Product(null, request.name(), request.price(), request.stock(), "OFF_SALE");
        product.setCategoryId(request.categoryId());
        productMapper.insertProduct(product);
        return product;
    }

    @Transactional
    public Product updateProduct(long id, UpdateProductRequest request) {
        if (request.categoryId() != null && categoryMapper.findById(request.categoryId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分类不存在");
        }
        if (productMapper.updateDetails(id, request) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        productCache.evictAfterCommit();
        return productMapper.findById(id);
    }

    @Transactional
    public Product updateProductStatus(long id, UpdateProductStatusRequest request) {
        if (productMapper.updateStatus(id, request.status()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        productCache.evictAfterCommit("ON_SALE".equals(request.status()) ? id : null);
        return productMapper.findById(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductSearchPage searchProducts(int page, int size, Long categoryId, String keyword) {
        String literalKeyword = literalSearchKeyword(keyword);
        long offset = (long) (page - 1) * size;
        long total = productMapper.countSearchProducts(categoryId, literalKeyword);
        List<ProductSearchItem> items = productMapper.searchProducts(categoryId, literalKeyword, offset, size);
        return new ProductSearchPage(page, size, total, items);
    }

    private String literalSearchKeyword(String keyword) {
        // 搜索词按普通文本匹配，先转义转义符自身，再处理 LIKE 通配符。
        return keyword == null || keyword.isBlank() ? null
                : keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
