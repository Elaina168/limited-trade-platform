package com.elaina.limitedtrade.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/sql/product-query-data.sql")
class SecurityWorkflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareTestAccounts() {
        // 明文仅为隔离测试数据；开发账号使用随机密码。
        String hash = new BCryptPasswordEncoder().encode("LearningTest2026!");
        jdbcTemplate.update("""
                INSERT INTO app_user (id, username, password_hash, role)
                VALUES (7001, 'test_admin', ?, 'ADMIN'), (7002, 'test_user', ?, 'USER')
                """, hash, hash);
    }

    @Test
    void csrfEndpointReturnsTokenBeforeLogin() throws Exception {
        var result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        assertNotNull(result.getRequest().getSession(false));
    }

    @Test
    void anonymousCurrentUserIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void databaseLoginPersistsIdentityWithoutExposingPassword() throws Exception {
        MockHttpSession session = loginAs("test_admin");
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7001))
                .andExpect(jsonPath("$.username").value("test_admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void wrongPasswordDoesNotAuthenticate() throws Exception {
        var result = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "test_admin").param("password", "wrong-password"))
                .andExpect(status().isUnauthorized()).andReturn();
        mockMvc.perform(get("/api/auth/me").session((MockHttpSession) result.getRequest().getSession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUsernameIsAlsoUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "missing_user").param("password", "LearningTest2026!"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "test_admin").param("password", "LearningTest2026!"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousAdminWriteWithCsrfIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/admin/products").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"商品\",\"price\":10,\"stock\":1}"))
                .andExpect(status().isUnauthorized());
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Integer.class));
    }

    @Test
    void ordinaryUserCannotWriteAdminProducts() throws Exception {
        MockHttpSession session = loginAs("test_user");
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7002))
                .andExpect(jsonPath("$.role").value("USER"));
        mockMvc.perform(post("/api/admin/products").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"商品\",\"price\":10,\"stock\":1}"))
                .andExpect(status().isForbidden());
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Integer.class));
    }

    @Test
    void administratorCanWriteProductsAfterDatabaseLogin() throws Exception {
        MockHttpSession session = loginAs("test_admin");
        mockMvc.perform(post("/api/admin/products").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"管理员商品\",\"price\":10,\"stock\":1}"))
                .andExpect(status().isCreated());
        assertEquals(4, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Integer.class));
    }

    @Test
    void administratorWriteWithoutCsrfIsForbidden() throws Exception {
        MockHttpSession session = loginAs("test_admin");
        mockMvc.perform(post("/api/admin/products").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"商品\",\"price\":10,\"stock\":1}"))
                .andExpect(status().isForbidden());
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Integer.class));
    }

    @Test
    void administratorWriteWithInvalidCsrfIsForbidden() throws Exception {
        MockHttpSession session = loginAs("test_admin");
        mockMvc.perform(post("/api/admin/products").session(session).with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"商品\",\"price\":10,\"stock\":1}"))
                .andExpect(status().isForbidden());
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Integer.class));
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        MockHttpSession session = loginAs("test_admin");
        mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutCsrfDoesNotEndSession() throws Exception {
        MockHttpSession session = loginAs("test_admin");
        mockMvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    private MockHttpSession loginAs(String username) throws Exception {
        var result = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", username).param("password", "LearningTest2026!"))
                .andExpect(status().isNoContent()).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }
}
