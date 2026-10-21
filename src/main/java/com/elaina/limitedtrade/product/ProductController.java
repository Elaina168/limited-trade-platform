package com.elaina.limitedtrade.product;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "商品")
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @Operation(summary = "查询在售商品列表", description = "匿名可读；返回在售商品数组。")
    public List<ProductListItem> listProducts() {
        return productService.listOnSaleProducts().stream()
                .map(product -> new ProductListItem(product.getId(), product.getName(), product.getPrice(),
                        product.getStock(), product.getStatus())).toList();
    }

    @GetMapping("/search")
    @Operation(summary = "分页搜索在售商品", description = "page默认1，size默认20且最多100，可按categoryId、keyword筛选；关键词按普通文本匹配。")
    public ProductSearchPage searchProducts(
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) @Size(max = 100) String keyword) {
        return productService.searchProducts(page, size, categoryId, keyword);
    }
}
