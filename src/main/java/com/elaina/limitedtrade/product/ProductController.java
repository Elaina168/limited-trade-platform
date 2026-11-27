package com.elaina.limitedtrade.product;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
        return productService.listOnSaleProducts().stream().map(ProductListItem::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询单个在售商品", description = "匿名可读；不存在或已下架返回404。")
    public ProductListItem getProduct(@PathVariable @Positive long id) {
        return ProductListItem.from(productService.getOnSaleProduct(id));
    }

    @GetMapping("/search")
    @Operation(summary = "分页搜索在售商品", description = "支持关键词分词/文本匹配、高亮、分类聚合统计、价格区间、仅看有货与排序；ES不可用时自动降级MySQL。")
    public ProductSearchPage searchProducts(
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @PositiveOrZero BigDecimal minPrice,
            @RequestParam(required = false) @PositiveOrZero BigDecimal maxPrice,
            @RequestParam(defaultValue = "false") boolean inStockOnly,
            @RequestParam(required = false) @Pattern(regexp = "(?:default|relevance|price_asc|price_desc|id_asc)?") String sort) {
        return productService.searchProducts(
                new ProductSearchCriteria(page, size, categoryId, keyword, minPrice, maxPrice, inStockOnly, sort));
    }

    @GetMapping("/suggest")
    @Operation(summary = "商品搜索词联想", description = "根据输入前缀或词根返回最多10条在售商品名称建议。")
    public List<String> suggestProducts(
            @RequestParam(required = false) @Size(max = 100) String prefix,
            @RequestParam(defaultValue = "8") @Positive @Max(20) int limit) {
        return productService.suggestProducts(prefix, limit);
    }
}
