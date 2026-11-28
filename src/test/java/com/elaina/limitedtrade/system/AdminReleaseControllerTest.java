package com.elaina.limitedtrade.system;

import com.elaina.limitedtrade.auth.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminReleaseController.class)
@Import(SecurityConfig.class)
class AdminReleaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReleaseInfoService releaseInfoService;

    @MockitoBean
    private ApplicationAvailability availability;

    private static SchemaMigrationMapper fullSchemaMapper() {
        SchemaMigrationMapper mapper = Mockito.mock(SchemaMigrationMapper.class);
        when(mapper.findPresentTables()).thenReturn(List.of(
                "product", "app_user", "trade_order", "activity", "order_timeout_outbox",
                "product_category", "cart_item", "user_address", "order_item", "order_address",
                "order_shipment", "activity_coupon", "user_coupon", "agent_conversation",
                "agent_conversation_turn", "agent_conversation_visible_turn", "agent_budget_gate",
                "agent_budget_request", "agent_budget_call", "activity_purchase_reservation",
                "product_search_pending"));
        when(mapper.findPresentColumns()).thenReturn(List.of(
                "trade_order.paid_at", "trade_order.order_type", "activity.per_user_limit"));
        when(mapper.findPresentIndexes()).thenReturn(List.of(
                "trade_order.idx_trade_order_status_expires"));
        return mapper;
    }

    @Test
    void serviceDetectsAllEighteenMigrationsAndRejectsUnappliedBreakingMigration() {
        Properties props = new Properties();
        props.setProperty("artifact", "limited-trade-platform");
        props.setProperty("version", "0.0.1-SNAPSHOT");
        props.setProperty("time", "2026-11-28T05:00:00Z");
        props.setProperty("git.commit", "2e5d310");
        props.setProperty("releaseLabel", "2026.11.28-r1");
        props.setProperty("requiredMigration", "018_product_search_sync");
        ReleaseInfoService okService = new ReleaseInfoService(
                fullSchemaMapper(),
                Optional.of(new BuildProperties(props)),
                "drill-node-a", "mysql", true, true, true, true, true, false);
        ReleaseInfoService.ReleaseSnapshot ok = okService.snapshot();
        assertTrue(ok.database().compatible());
        assertEquals("018_product_search_sync", ok.database().latestDetectedMigration());
        assertEquals(18, ok.database().appliedMigrations().size());
        assertEquals("2e5d310", ok.application().gitSha());
        assertEquals("2026.11.28-r1", ok.application().releaseLabel());

        props.setProperty("requiredMigration", "019_breaking_schema_drill");
        ReleaseInfoService incompatibleService = new ReleaseInfoService(
                fullSchemaMapper(),
                Optional.of(new BuildProperties(props)),
                "drill-node-a", "mysql", true, true, true, true, true, false);
        ReleaseInfoService.ReleaseSnapshot bad = incompatibleService.snapshot();
        assertFalse(bad.database().compatible());
        assertEquals(List.of("019_breaking_schema_drill"), bad.database().missingMigrations());
    }

    @Test
    void anonymousAndRegularUsersCannotReadReleaseEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/operations/release")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/operations/release")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousReadinessRequiresBothApplicationAndSchemaReadiness() throws Exception {
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
        when(releaseInfoService.databaseState()).thenReturn(
                new ReleaseInfoService.DatabaseMigrationState("018", "017", List.of(), List.of("018"), false),
                new ReleaseInfoService.DatabaseMigrationState("018", "018", List.of("018"), List.of(), true));

        mockMvc.perform(get("/internal/ready")).andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/internal/ready")).andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/internal/ready")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminReceives200WhenCompatibleAnd503WhenMigrationMissing() throws Exception {
        var appVer = new ReleaseInfoService.ApplicationVersion(
                "limited-trade-platform", "0.0.1-SNAPSHOT", Instant.parse("2026-11-28T05:00:00Z"),
                "2e5d310", "2026.11.28-r1", "container-app");
        var features = new ReleaseInfoService.FeatureFlags("mysql", true, true, true, true, true, false);
        when(releaseInfoService.snapshot()).thenReturn(
                new ReleaseInfoService.ReleaseSnapshot(
                        java.time.LocalDateTime.of(2026, 11, 28, 13, 30),
                        appVer,
                        new ReleaseInfoService.DatabaseMigrationState(
                                "018_product_search_sync", "018_product_search_sync",
                                List.of("schema.sql", "018_product_search_sync"), List.of(), true),
                        features),
                new ReleaseInfoService.ReleaseSnapshot(
                        java.time.LocalDateTime.of(2026, 11, 28, 13, 31),
                        appVer,
                        new ReleaseInfoService.DatabaseMigrationState(
                                "019_future", "018_product_search_sync",
                                List.of("schema.sql", "018_product_search_sync"), List.of("019_future"), false),
                        features));

        mockMvc.perform(get("/api/admin/operations/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.gitSha").value("2e5d310"))
                .andExpect(jsonPath("$.database.compatible").value(true));

        mockMvc.perform(get("/api/admin/operations/release"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.database.compatible").value(false))
                .andExpect(jsonPath("$.database.missingMigrations[0]").value("019_future"));
    }
}
