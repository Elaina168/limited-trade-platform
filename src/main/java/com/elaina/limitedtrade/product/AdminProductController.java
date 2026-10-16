package com.elaina.limitedtrade.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final ProductService productService;

    public AdminProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<Product> createProduct(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(request));
    }

    @PutMapping("/{id}")
    public Product updateProduct(@PathVariable @Positive long id,
                                 @Valid @RequestBody UpdateProductRequest request) {
        return productService.updateProduct(id, request);
    }

    @PatchMapping("/{id}/status")
    public Product updateProductStatus(@PathVariable @Positive long id,
                                       @Valid @RequestBody UpdateProductStatusRequest request) {
        return productService.updateProductStatus(id, request);
    }
}
