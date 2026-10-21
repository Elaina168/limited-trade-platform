package com.elaina.limitedtrade.category;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "分类管理")
@RestController
@RequestMapping("/api/admin/categories")
public class AdminCategoryController {
    private final CategoryService service;

    public AdminCategoryController(CategoryService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "创建商品分类", description = "分类名称重复返回409；成功返回201。")
    public ResponseEntity<Category> createCategory(@Valid @RequestBody SaveCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCategory(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "重命名商品分类", description = "分类不存在返回404，名称重复返回409。")
    public Category renameCategory(@PathVariable @Positive long id,
                                   @Valid @RequestBody SaveCategoryRequest request) {
        return service.renameCategory(id, request);
    }
}
