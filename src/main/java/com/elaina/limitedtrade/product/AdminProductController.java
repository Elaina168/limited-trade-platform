package com.elaina.limitedtrade.product;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "商品管理")
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final ProductService productService;

    public AdminProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @Operation(summary = "查询全部商品（管理员）", description = "包含上架和下架商品。")
    public List<Product> listProducts() {
        return productService.listAdminProducts();
    }

    @GetMapping("/page")
    @Operation(summary = "分页搜索商品（管理员）", description = "page默认1，size默认20且最多100，支持名称、分类和上下架状态筛选。")
    public AdminProductPage pageProducts(
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) @Pattern(regexp = "(?:ON_SALE|OFF_SALE)?") String status) {
        return productService.pageAdminProducts(page, size, keyword, categoryId, status);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询单个商品（管理员）", description = "支持读取下架商品，商品不存在返回404。")
    public Product getProduct(@PathVariable @Positive long id) {
        return productService.getAdminProduct(id);
    }

    @PostMapping
    @Operation(summary = "创建商品", description = "新商品初始为OFF_SALE；成功返回201，可选分类必须存在。")
    public ResponseEntity<Product> createProduct(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改商品资料", description = "更新名称、价格、分类，不通过此接口修改库存；不存在返回404。")
    public Product updateProduct(@PathVariable @Positive long id,
                                 @Valid @RequestBody UpdateProductRequest request) {
        return productService.updateProduct(id, request);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "修改商品上下架状态", description = "修改ON_SALE或OFF_SALE；商品不存在返回404。")
    public Product updateProductStatus(@PathVariable @Positive long id,
                                       @Valid @RequestBody UpdateProductStatusRequest request) {
        return productService.updateProductStatus(id, request);
    }

    @PostMapping("/reindex")
    @Operation(summary = "全量重建商品搜索索引", description = "管理员重建Elasticsearch在售商品索引。")
    public java.util.Map<String, Object> reindexSearch() {
        int indexed = productService.reindexSearch();
        return java.util.Map.of("indexed", indexed);
    }
}
