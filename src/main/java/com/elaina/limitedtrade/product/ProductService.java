package com.elaina.limitedtrade.product;

import java.math.BigDecimal;
import java.util.List;
import com.elaina.limitedtrade.category.CategoryMapper;
import com.elaina.limitedtrade.product.search.ProductSearchCoordinator;

import org.springframework.context.ApplicationEventPublisher;
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
    private final ProductSearchCoordinator searchCoordinator;
    private final ApplicationEventPublisher eventPublisher;

    public ProductService(ProductMapper productMapper,
                          ProductCache productCache,
                          CategoryMapper categoryMapper,
                          ProductSearchCoordinator searchCoordinator,
                          ApplicationEventPublisher eventPublisher) {
        this.productMapper = productMapper;
        this.productCache = productCache;
        this.categoryMapper = categoryMapper;
        this.searchCoordinator = searchCoordinator;
        this.eventPublisher = eventPublisher;
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
        eventPublisher.publishEvent(ProductChangedEvent.of(product.getId()));
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
        eventPublisher.publishEvent(ProductChangedEvent.of(id));
        return productMapper.findById(id);
    }

    @Transactional
    public Product updateProductStatus(long id, UpdateProductStatusRequest request) {
        if (productMapper.updateStatus(id, request.status()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        productCache.evictAfterCommit("ON_SALE".equals(request.status()) ? id : null);
        eventPublisher.publishEvent(ProductChangedEvent.of(id));
        return productMapper.findById(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductSearchPage searchProducts(int page, int size, Long categoryId, String keyword) {
        return searchProducts(new ProductSearchCriteria(page, size, categoryId, keyword, null, null, false, null));
    }

    public ProductSearchPage searchProducts(ProductSearchCriteria criteria) {
        if (criteria.minPrice() != null && criteria.maxPrice() != null
                && criteria.minPrice().compareTo(criteria.maxPrice()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最低价格不能高于最高价格");
        }
        return searchCoordinator.search(criteria);
    }

    public List<String> suggestProducts(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        return searchCoordinator.suggest(prefix, limit);
    }

    public int reindexSearch() {
        return searchCoordinator.reindexAll();
    }

    private String literalSearchKeyword(String keyword) {
        // 搜索词按普通文本匹配，先转义转义符自身，再处理 LIKE 通配符。
        return keyword == null || keyword.isBlank() ? null
                : keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
