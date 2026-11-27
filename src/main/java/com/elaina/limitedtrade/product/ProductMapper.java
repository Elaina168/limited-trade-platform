package com.elaina.limitedtrade.product;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProductMapper {

    Product findByIdForUpdate(long id);

    List<Product> findOnSaleProducts();

    List<Product> findAllProducts();
    long countAdminProducts(@Param("categoryId") Long categoryId, @Param("keyword") String keyword, @Param("status") String status);
    List<Product> pageAdminProducts(@Param("categoryId") Long categoryId, @Param("keyword") String keyword,
                                    @Param("status") String status, @Param("offset") long offset, @Param("limit") int limit);

    Product findById(long id);

    int insertProduct(Product product);

    int updateDetails(@Param("id") long id, @Param("request") UpdateProductRequest request);

    int updateStatus(@Param("id") long id, @Param("status") String status);

    int deductStock(@Param("id") long id, @Param("quantity") int quantity);

    int restoreStock(@Param("id") long id, @Param("quantity") int quantity);

    long countSearchProducts(@Param("categoryId") Long categoryId, @Param("keyword") String keyword);

    List<ProductSearchItem> searchProducts(@Param("categoryId") Long categoryId,
                                          @Param("keyword") String keyword,
                                          @Param("offset") long offset, @Param("limit") int limit);

    long countFilteredSearchProducts(@Param("categoryId") Long categoryId,
                                     @Param("keyword") String keyword,
                                     @Param("minPrice") java.math.BigDecimal minPrice,
                                     @Param("maxPrice") java.math.BigDecimal maxPrice,
                                     @Param("inStockOnly") boolean inStockOnly);

    List<ProductSearchItem> searchFilteredProducts(@Param("categoryId") Long categoryId,
                                                   @Param("keyword") String keyword,
                                                   @Param("minPrice") java.math.BigDecimal minPrice,
                                                   @Param("maxPrice") java.math.BigDecimal maxPrice,
                                                   @Param("inStockOnly") boolean inStockOnly,
                                                   @Param("sort") String sort,
                                                   @Param("offset") long offset,
                                                   @Param("limit") int limit);

    List<CategoryFacet> aggregateSearchCategories(@Param("keyword") String keyword,
                                                  @Param("minPrice") java.math.BigDecimal minPrice,
                                                  @Param("maxPrice") java.math.BigDecimal maxPrice,
                                                  @Param("inStockOnly") boolean inStockOnly);

    List<String> suggestProductNames(@Param("keyword") String keyword, @Param("limit") int limit);

    List<ProductSearchItem> findAllOnSaleSearchItems();

    List<ProductSearchItem> findSearchItemsByIds(@Param("ids") List<Long> ids);
}
