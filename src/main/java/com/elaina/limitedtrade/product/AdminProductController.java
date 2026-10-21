package com.elaina.limitedtrade.product;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
