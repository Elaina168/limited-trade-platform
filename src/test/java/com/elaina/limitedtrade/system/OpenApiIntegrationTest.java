package com.elaina.limitedtrade.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    private JsonNode document() throws Exception {
        return json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test void anonymousCanReadDocumentAndLocalUiResources() throws Exception {
        mvc.perform(get("/api-docs.html")).andExpect(status().isOk());
        mvc.perform(get("/assets/api-docs.js")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/swagger-ui.css")).andExpect(status().isOk());
        assertTrue(document().path("openapi").asText().startsWith("3."));
    }

    @Test void documentCoversEveryApiMappingAndFilterLoginLogout() throws Exception {
        JsonNode paths = document().path("paths");
        Set<String> expected = new HashSet<>(Set.of("post /api/auth/login", "post /api/auth/logout"));
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            for (String path : mapping.getPatternValues()) {
                if (path.startsWith("/api/")) {
                    mapping.getMethodsCondition().getMethods().forEach(method ->
                            expected.add(method.name().toLowerCase() + " " + path));
                }
            }
        });
        Set<String> actual = new HashSet<>();
        paths.fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(operation -> {
            actual.add(operation.getKey() + " " + path.getKey());
            assertFalse(operation.getValue().path("summary").asText().isBlank(), path.getKey());
        }));
        assertEquals(expected, actual);
    }

    @Test void documentsSessionCsrfAndAdminPermissionsWithoutJwtOrPrincipalLeak() throws Exception {
        JsonNode doc = document();
        JsonNode schemes = doc.at("/components/securitySchemes");
        assertEquals("cookie", schemes.path("SessionAuth").path("in").asText());
        assertEquals("JSESSIONID", schemes.path("SessionAuth").path("name").asText());
        assertEquals("X-CSRF-TOKEN", schemes.path("CsrfToken").path("name").asText());
        JsonNode read = doc.path("paths").path("/api/orders").path("get");
        assertTrue(read.path("security").get(0).has("SessionAuth"));
        JsonNode write = doc.path("paths").path("/api/orders").path("post");
        assertTrue(write.path("security").get(0).has("SessionAuth"));
        assertTrue(write.path("security").get(0).has("CsrfToken"));
        assertTrue(doc.path("paths").path("/api/admin/orders").path("get").path("description").asText().contains("ADMIN"));
        String encoded = doc.toString();
        assertFalse(encoded.contains("passwordHash"));
        assertFalse(encoded.contains("PlatformUserDetails"));
        assertFalse(encoded.contains("bearerFormat"));
        assertTrue(doc.path("paths").path("/api/products").path("get").path("security").isEmpty());
    }

    @Test void paginationAndDynamicCreationStatusesMatchExistingContract() throws Exception {
        JsonNode doc = document();
        JsonNode list = doc.path("paths").path("/api/orders").path("get");
        assertEquals("#/components/schemas/OrderPageResponse", list.path("responses").path("200")
                .path("content").path("application/json").path("schema").path("$ref").asText());
        assertEquals(3, list.path("parameters").size());
        JsonNode create = doc.path("paths").path("/api/orders").path("post").path("responses");
        for (String code : Set.of("200", "201", "400", "401", "403", "404", "409", "429", "503"))
            assertTrue(create.has(code), code);
        assertTrue(create.path("429").path("headers").has("Retry-After"));
        assertTrue(create.path("201").path("headers").has("X-Trace-Id"));
        JsonNode address = doc.path("paths").path("/api/addresses/{id}").path("delete").path("responses");
        assertTrue(address.has("204"));
        assertFalse(address.has("200"));
    }

    @Test void filterEndpointsUseFormLoginAndNoContentResponses() throws Exception {
        JsonNode paths = document().path("paths");
        JsonNode login = paths.path("/api/auth/login").path("post");
        assertTrue(login.path("requestBody").path("content").has("application/x-www-form-urlencoded"));
        assertTrue(login.path("responses").has("204"));
        assertFalse(login.path("responses").path("204").has("content"));
        assertTrue(paths.path("/api/auth/logout").path("post").path("responses").has("204"));
        assertTrue(paths.path("/api/auth/csrf").path("get").path("parameters").isMissingNode());
    }

    @Test void creationNoContentAndRequestSchemasDescribeActualContracts() throws Exception {
        JsonNode doc = document();
        JsonNode paths = doc.path("paths");
        for (String path : Set.of("/api/auth/register", "/api/addresses", "/api/admin/products",
                "/api/admin/categories", "/api/admin/activities", "/api/admin/coupons")) {
            JsonNode responses = paths.path(path).path("post").path("responses");
            assertTrue(responses.has("201"), path);
            assertFalse(responses.has("200"), path);
            assertFalse(responses.path("201").path("content").isMissingNode(), path);
        }
        for (String path : Set.of("/api/cart/items/{productId}", "/api/addresses/{id}/default")) {
            JsonNode responses = paths.path(path).path("put").path("responses");
            assertTrue(responses.has("204"), path);
            assertFalse(responses.path("204").has("content"), path);
        }
        JsonNode schemas = doc.at("/components/schemas");
        assertEquals("password", schemas.path("RegisterRequest").path("properties").path("password").path("format").asText());
        assertTrue(schemas.path("RegisterRequest").path("required").toString().contains("password"));
        assertTrue(schemas.path("CreateOrderRequest").path("properties").path("requestId").path("description").asText().contains("幂等"));
        JsonNode parameters = paths.path("/api/orders").path("get").path("parameters");
        JsonNode size = java.util.stream.StreamSupport.stream(parameters.spliterator(), false)
                .filter(parameter -> parameter.path("name").asText().equals("size")).findFirst().orElseThrow();
        assertEquals(20, size.path("schema").path("default").asInt());
        assertEquals(100, size.path("schema").path("maximum").asInt());
    }

    @Test void documentationAccessDoesNotRelaxBusinessSecurity() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/orders")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").param("username", "absent").param("password", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", "absent").param("password", "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/not-a-documented-route")).andExpect(status().isUnauthorized());
    }
}
