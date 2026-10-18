package com.elaina.limitedtrade.product;

import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductListItem> listProducts() {
        return productService.listOnSaleProducts().stream()
                .map(product -> new ProductListItem(product.getId(), product.getName(), product.getPrice(),
                        product.getStock(), product.getStatus())).toList();
    }

    @GetMapping("/search")
    public ProductSearchPage searchProducts(
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) @Size(max = 100) String keyword) {
        return productService.searchProducts(page, size, categoryId, keyword);
    }
}
