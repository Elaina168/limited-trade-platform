package com.elaina.limitedtrade.product;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql({"/sql/category-test-data.sql", "/sql/catalog-product-data.sql"})
@WithMockUser(roles = "ADMIN")
class ProductCategoryApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper json;

    @Test
    void createProductPersistsOptionalCategoryAndStillStartsOffSale() throws Exception {
        var response = mvc.perform(post("/api/admin/products").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新分类商品\",\"price\":29.90,\"stock\":8,\"categoryId\":12001}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.categoryId").value(12001))
                .andExpect(jsonPath("$.status").value("OFF_SALE")).andReturn().getResponse().getContentAsString();
        long id=json.readTree(response).get("id").asLong();
        assertEquals(12001L, jdbc.queryForObject("SELECT category_id FROM product WHERE id=?", Long.class, id));
    }

    @Test
    void updateCanChangeAndClearCategoryWithoutChangingStockOrStatus() throws Exception {
        mvc.perform(put("/api/admin/products/10001").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新版键盘\",\"price\":101,\"categoryId\":12002}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categoryId").value(12002))
                .andExpect(jsonPath("$.stock").value(10)).andExpect(jsonPath("$.status").value("ON_SALE"));
        assertEquals(12002L, jdbc.queryForObject("SELECT category_id FROM product WHERE id=10001", Long.class));
        mvc.perform(put("/api/admin/products/10001").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新版键盘\",\"price\":101,\"categoryId\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categoryId").isEmpty());
        assertNull(jdbc.queryForObject("SELECT category_id FROM product WHERE id=10001", Long.class));
    }

    @Test
    void omittedCategoryOnFullPutMeansUnclassified() throws Exception {
        mvc.perform(put("/api/admin/products/10001").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"旧格式更新\",\"price\":101}"))
                .andExpect(status().isOk());
        assertNull(jdbc.queryForObject("SELECT category_id FROM product WHERE id=10001", Long.class));
    }

    @Test
    void nonexistentCategoryCannotCreateOrChangeProduct() throws Exception {
        mvc.perform(post("/api/admin/products").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新商品\",\"price\":29.90,\"stock\":8,\"categoryId\":12999}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/products/10001").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"不应更新\",\"price\":1,\"categoryId\":12999}"))
                .andExpect(status().isNotFound());
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class));
        assertEquals("机械键盘", jdbc.queryForObject("SELECT name FROM product WHERE id=10001", String.class));
        assertEquals(12001L, jdbc.queryForObject("SELECT category_id FROM product WHERE id=10001", Long.class));
    }

    @ParameterizedTest
    @ValueSource(strings={"0", "-1", "1.5", "9223372036854775808"})
    void invalidCategoryIdCannotWriteProduct(String id) throws Exception {
        mvc.perform(post("/api/admin/products").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新商品\",\"price\":10,\"stock\":1,\"categoryId\":"+id+"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/products/10001").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新商品\",\"price\":10,\"categoryId\":"+id+"}"))
                .andExpect(status().isBadRequest());
        assertEquals("机械键盘", jdbc.queryForObject("SELECT name FROM product WHERE id=10001", String.class));
    }

    @Test
    void categoryRenameAndProductReassignmentAreVisibleInUncachedSearch() throws Exception {
        mvc.perform(get("/api/products"));
        mvc.perform(put("/api/admin/categories/12001").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"电脑外设\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/products/search").param("categoryId", "12001").param("keyword", "机械"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].categoryName").value("电脑外设"));
        mvc.perform(put("/api/admin/products/10001").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"机械键盘\",\"price\":100,\"categoryId\":12002}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/products/search").param("categoryId", "12001").param("keyword", "机械"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/products/search").param("categoryId", "12002").param("keyword", "机械"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].categoryName").value("生活用品"));
    }
}
