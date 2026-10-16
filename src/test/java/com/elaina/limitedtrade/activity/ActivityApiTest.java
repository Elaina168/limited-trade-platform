package com.elaina.limitedtrade.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/sql/order-action-test-data.sql")
@WithMockUser(roles = "ADMIN")
class ActivityApiTest {

    private static final String VALID_BODY = """
            {"productId":9201,"name":"限量活动","totalQuantity":2,
             "startsAt":"2026-10-16T10:00:00","endsAt":"2026-10-16T11:00:00"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createActivityStartsWithFullQuota() throws Exception {
        mockMvc.perform(post("/api/admin/activities").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":9201,"name":"限量活动","totalQuantity":2,
                                 "startsAt":"2026-10-16T10:00:00","endsAt":"2026-10-16T11:00:00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.remainingQuantity").value(2));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }

    @Test
    void anonymousCanReadCreatedActivity() throws Exception {
        String response = mockMvc.perform(post("/api/admin/activities").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(response).get("id").asLong();
        mockMvc.perform(get("/api/activities/" + id)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(9201))
                .andExpect(jsonPath("$.totalQuantity").value(2))
                .andExpect(jsonPath("$.startsAt").value("2026-10-16T10:00:00"));
    }

    @Test
    @WithAnonymousUser
    void anonymousCannotCreateActivity() throws Exception {
        mockMvc.perform(post("/api/admin/activities").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void ordinaryUserCannotCreateActivity() throws Exception {
        mockMvc.perform(post("/api/admin/activities").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void creationRequiresCsrf() throws Exception {
        mockMvc.perform(post("/api/admin/activities").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"productId", "name", "totalQuantity", "startsAt", "endsAt"})
    void requiredFieldCannotBeOmitted(String field) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_BODY);
        body.remove(field);
        mockMvc.perform(post("/api/admin/activities").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveQuota(int quantity) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_BODY);
        body.put("totalQuantity", quantity);
        mockMvc.perform(post("/api/admin/activities").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsFractionalQuota() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_BODY);
        body.put("totalQuantity", 1.5);
        mockMvc.perform(post("/api/admin/activities").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "long"})
    void rejectsBlankOrTooLongName(String name) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_BODY);
        body.put("name", "long".equals(name) ? "名".repeat(101) : name);
        mockMvc.perform(post("/api/admin/activities").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-16T10:00:00", "2026-10-16T09:59:59", "2026-10-16T10:00:00.900"})
    void endMustBeAfterStartAtStoredSecondPrecision(String endsAt) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_BODY);
        body.put("endsAt", endsAt);
        mockMvc.perform(post("/api/admin/activities").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM activity", Integer.class));
    }

    @Test
    void nonexistentProductIsNotFound() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(VALID_BODY);
        body.put("productId", 999999);
        mockMvc.perform(post("/api/admin/activities").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonexistentActivityIsNotFound() throws Exception {
        mockMvc.perform(get("/api/activities/999999")).andExpect(status().isNotFound());
    }
}
