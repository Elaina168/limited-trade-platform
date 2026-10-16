package com.elaina.limitedtrade.product;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/sql/product-query-data.sql")
@WithMockUser(roles = "ADMIN")
class AdminProductApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createProductPersistsItWithGeneratedIdAndOffSaleStatus() throws Exception {
        String response = mockMvc.perform(post("/api/admin/products").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"新商品","price":29.90,"stock":8}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("新商品"))
                .andExpect(jsonPath("$.stock").value(8))
                .andExpect(jsonPath("$.status").value("OFF_SALE"))
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(response).get("id").asLong();
        assertTrue(id > 0);
        assertEquals("新商品", jdbcTemplate.queryForObject(
                "SELECT name FROM product WHERE id = ?", String.class, id));
        assertEquals(new BigDecimal("29.90"), jdbcTemplate.queryForObject(
                "SELECT price FROM product WHERE id = ?", BigDecimal.class, id));
        assertEquals("OFF_SALE", jdbcTemplate.queryForObject(
                "SELECT status FROM product WHERE id = ?", String.class, id));
    }

    @Test
    void updateProductChangesDetailsAndPreservesStockAndStatus() throws Exception {
        mockMvc.perform(put("/api/admin/products/9001").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"新版键盘","price":149.90}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新版键盘"))
                .andExpect(jsonPath("$.price").value(149.90))
                .andExpect(jsonPath("$.stock").value(10))
                .andExpect(jsonPath("$.status").value("ON_SALE"));

        assertEquals(new BigDecimal("149.90"), jdbcTemplate.queryForObject(
                "SELECT price FROM product WHERE id = 9001", BigDecimal.class));
        assertEquals(10, jdbcTemplate.queryForObject(
                "SELECT stock FROM product WHERE id = 9001", Integer.class));
    }

    @Test
    void repeatedStatusChangesSucceedAndControlPublicVisibility() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(patch("/api/admin/products/9001/status").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"OFF_SALE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stock").value(10))
                    .andExpect(jsonPath("$.status").value("OFF_SALE"));
        }
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(9002));

        mockMvc.perform(patch("/api/admin/products/9001/status").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ON_SALE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(9001));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\" \",\"price\":10,\"stock\":1}",
            "{\"price\":10,\"stock\":1}",
            "{\"name\":\"商品\",\"price\":0,\"stock\":1}",
            "{\"name\":\"商品\",\"price\":-1,\"stock\":1}",
            "{\"name\":\"商品\",\"price\":1.001,\"stock\":1}",
            "{\"name\":\"商品\",\"price\":100000000,\"stock\":1}",
            "{\"name\":\"商品\",\"stock\":1}",
            "{\"name\":\"商品\",\"price\":10,\"stock\":-1}",
            "{\"name\":\"商品\",\"price\":10}",
            "{\"name\":\"商品\",\"price\":10,\"stock\":1.5}",
            "{\"name\":\"商品\",\"price\":10,\"stock\":2147483648}"
    })
    void invalidCreateRequestDoesNotInsertProduct(String body) throws Exception {
        mockMvc.perform(post("/api/admin/products").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Integer.class));
    }

    @Test
    void tooLongNameIsRejectedBeforeInsert() throws Exception {
        String body = "{\"name\":\"" + "字".repeat(101) + "\",\"price\":10,\"stock\":1}";
        mockMvc.perform(post("/api/admin/products").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\" \",\"price\":10}", "{\"name\":\"商品\",\"price\":-1}"})
    void invalidUpdateRequestDoesNotModifyProduct(String body) throws Exception {
        mockMvc.perform(put("/api/admin/products/9001").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertEquals(new BigDecimal("129.00"), jdbcTemplate.queryForObject(
                "SELECT price FROM product WHERE id = 9001", BigDecimal.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":\"DELETED\"}", "{\"status\":\"on_sale\"}", "{}"})
    void invalidStatusDoesNotModifyProduct(String body) throws Exception {
        mockMvc.perform(patch("/api/admin/products/9001/status").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertEquals("ON_SALE", jdbcTemplate.queryForObject(
                "SELECT status FROM product WHERE id = 9001", String.class));
    }

    @Test
    void updateMissingProductReturnsNotFound() throws Exception {
        mockMvc.perform(put("/api/admin/products/999999").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"商品\",\"price\":10}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void changeMissingProductStatusReturnsNotFound() throws Exception {
        mockMvc.perform(patch("/api/admin/products/999999/status").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ON_SALE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void negativeProductIdIsRejected() throws Exception {
        mockMvc.perform(put("/api/admin/products/-1").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"商品\",\"price\":10}"))
                .andExpect(status().isBadRequest());
    }
}
