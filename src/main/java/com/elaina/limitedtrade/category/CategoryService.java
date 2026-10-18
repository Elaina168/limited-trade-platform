package com.elaina.limitedtrade.category;

import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CategoryService {
    private final CategoryMapper mapper;

    public CategoryService(CategoryMapper mapper) {
        this.mapper = mapper;
    }

    public List<Category> listCategories() {
        return mapper.findAll();
    }

    @Transactional
    public Category createCategory(SaveCategoryRequest request) {
        Category category = new Category();
        category.setName(request.name().strip());
        try {
            mapper.insertCategory(category);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "分类名称已存在", exception);
        }
        return category;
    }

    @Transactional
    public Category renameCategory(long id, SaveCategoryRequest request) {
        try {
            if (mapper.updateName(id, request.name().strip()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分类不存在");
            }
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "分类名称已存在", exception);
        }
        return mapper.findById(id);
    }
}
