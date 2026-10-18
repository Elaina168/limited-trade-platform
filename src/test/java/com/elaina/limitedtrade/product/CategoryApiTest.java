package com.elaina.limitedtrade.product;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/sql/category-test-data.sql")
class CategoryApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    @Test
    void anonymousCanListCategoriesInIdOrder() throws Exception {
        mvc.perform(get("/api/categories")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(12001))
                .andExpect(jsonPath("$[0].name").value("数码配件"))
                .andExpect(jsonPath("$[1].id").value(12002));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCreatesTrimmedCategoryWithGeneratedId() throws Exception {
        var body = mvc.perform(post("/api/admin/categories").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  运动用品  \"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("运动用品"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();
        assertTrue(id > 0);
        assertEquals("运动用品", jdbc.queryForObject("SELECT name FROM product_category WHERE id=?", String.class, id));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void renamePersistsAndRepeatedSameNameSucceeds() throws Exception {
        for (int i=0; i<2; i++) {
            mvc.perform(put("/api/admin/categories/12001").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"电脑配件\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(12001))
                    .andExpect(jsonPath("$.name").value("电脑配件"));
        }
        assertEquals("电脑配件", jdbc.queryForObject("SELECT name FROM product_category WHERE id=12001", String.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicateCreateReturnsConflictWithoutAddingRow() throws Exception {
        mvc.perform(post("/api/admin/categories").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" 数码配件 \"}"))
                .andExpect(status().isConflict());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM product_category", Integer.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicateRenameReturnsConflictAndKeepsOriginal() throws Exception {
        mvc.perform(put("/api/admin/categories/12002").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"数码配件\"}"))
                .andExpect(status().isConflict());
        assertEquals("生活用品", jdbc.queryForObject("SELECT name FROM product_category WHERE id=12002", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings={"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"  \"}"})
    @WithMockUser(roles = "ADMIN")
    void invalidNameCannotCreateOrRename(String body) throws Exception {
        mvc.perform(post("/api/admin/categories").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/categories/12001").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM product_category", Integer.class));
        assertEquals("数码配件", jdbc.queryForObject("SELECT name FROM product_category WHERE id=12001", String.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void nameOverFiftyCharactersIsRejected() throws Exception {
        var body = json.writeValueAsString(java.util.Map.of("name", "字".repeat(51)));
        mvc.perform(post("/api/admin/categories").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void missingCategoryCannotBeRenamed() throws Exception {
        mvc.perform(put("/api/admin/categories/12999").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"电脑配件\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void nonPositiveCategoryIdIsRejected() throws Exception {
        mvc.perform(put("/api/admin/categories/0").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"电脑配件\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousCannotMaintainCategories() throws Exception {
        mvc.perform(post("/api/admin/categories").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"电脑配件\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/categories/12001").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"电脑配件\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void normalUserCannotMaintainCategories() throws Exception {
        mvc.perform(post("/api/admin/categories").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"电脑配件\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/categories/12001").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"电脑配件\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminStillNeedsCsrfForBothWrites() throws Exception {
        mvc.perform(post("/api/admin/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"电脑配件\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/categories/12001").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"电脑配件\"}"))
                .andExpect(status().isForbidden());
    }
}
