package com.elaina.limitedtrade.system;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReleaseInfoService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseInfoService.class);

    private static final List<MigrationProbe> BASELINE_MIGRATIONS = List.of(
            new MigrationProbe("schema.sql", s -> s.tables().contains("product")),
            new MigrationProbe("002_app_user", s -> s.tables().contains("app_user")),
            new MigrationProbe("003_trade_order", s -> s.tables().contains("trade_order")),
            new MigrationProbe("004_order_payment_cancel", s -> s.columns().contains("trade_order.paid_at")),
            new MigrationProbe("005_order_timeout", s -> s.indexes().contains("trade_order.idx_trade_order_status_expires")),
            new MigrationProbe("006_activity", s -> s.tables().contains("activity")),
            new MigrationProbe("007_order_timeout_outbox", s -> s.tables().contains("order_timeout_outbox")),
            new MigrationProbe("008_product_category", s -> s.tables().contains("product_category")),
            new MigrationProbe("009_cart_item", s -> s.tables().contains("cart_item")),
            new MigrationProbe("010_user_address", s -> s.tables().contains("user_address")),
            new MigrationProbe("011_order_checkout", s -> s.tables().contains("order_item")
                    && s.tables().contains("order_address")
                    && s.columns().contains("trade_order.order_type")),
            new MigrationProbe("012_order_shipment", s -> s.tables().contains("order_shipment")),
            new MigrationProbe("013_activity_coupon", s -> s.tables().contains("activity_coupon")
                    && s.tables().contains("user_coupon")),
            new MigrationProbe("014_agent_conversation", s -> s.tables().contains("agent_conversation")
                    && s.tables().contains("agent_conversation_turn")),
            new MigrationProbe("015_agent_conversation_visible_history",
                    s -> s.tables().contains("agent_conversation_visible_turn")),
            new MigrationProbe("016_agent_usage_budget", s -> s.tables().contains("agent_budget_gate")
                    && s.tables().contains("agent_budget_request")
                    && s.tables().contains("agent_budget_call")),
            new MigrationProbe("017_activity_seckill", s -> s.tables().contains("activity_purchase_reservation")
                    && s.columns().contains("activity.per_user_limit")),
            new MigrationProbe("018_product_search_sync", s -> s.tables().contains("product_search_pending"))
    );

    private final SchemaMigrationMapper migrationMapper;
    private final Optional<BuildProperties> buildProperties;
    private final String instanceId;
    private final FeatureFlags features;
    private final String gitSha;
    private final String releaseLabel;
    private final String requiredMigration;

    public ReleaseInfoService(SchemaMigrationMapper migrationMapper,
                              Optional<BuildProperties> buildProperties,
                              @Value("${APP_INSTANCE_ID:local}") String instanceId,
                              @Value("${app.search.mode:mysql}") String searchMode,
                              @Value("${app.seckill.enabled:false}") boolean seckillEnabled,
                              @Value("${app.product-cache.enabled:false}") boolean productCacheEnabled,
                              @Value("${app.order-rate-limit.enabled:false}") boolean orderRateLimitEnabled,
                              @Value("${app.order-message.enabled:false}") boolean orderMessageEnabled,
                              @Value("${app.order-message.monitor.enabled:false}") boolean orderMessageMonitorEnabled,
                              @Value("${app.agent.enabled:false}") boolean agentEnabled) {
        this.migrationMapper = migrationMapper;
        this.buildProperties = buildProperties;
        this.instanceId = instanceId;
        this.features = new FeatureFlags(
                searchMode, seckillEnabled, productCacheEnabled,
                orderRateLimitEnabled, orderMessageEnabled, orderMessageMonitorEnabled, agentEnabled);
        this.gitSha = buildProperties.map(b -> b.get("git.commit")).orElse("unknown");
        this.releaseLabel = buildProperties.map(b -> b.get("releaseLabel")).orElse("local");
        this.requiredMigration = buildProperties.map(b -> b.get("requiredMigration")).orElse("018_product_search_sync");
    }

    public ReleaseSnapshot snapshot() {
        return new ReleaseSnapshot(
                LocalDateTime.now().withNano(0),
                new ApplicationVersion(
                        buildProperties.map(BuildProperties::getArtifact).orElse("limited-trade-platform"),
                        buildProperties.map(BuildProperties::getVersion).orElse("0.0.1-SNAPSHOT"),
                        buildProperties.map(BuildProperties::getTime).orElse(null), gitSha, releaseLabel, instanceId),
                databaseState(), features);
    }

    DatabaseMigrationState databaseState() {
        SchemaObjects objects;
        try {
            objects = new SchemaObjects(
                    new HashSet<>(migrationMapper.findPresentTables()),
                    new HashSet<>(migrationMapper.findPresentColumns()),
                    new HashSet<>(migrationMapper.findPresentIndexes()));
        } catch (DataAccessException error) {
            log.atError().addKeyValue("event", "release_schema_check_error")
                    .addKeyValue("exceptionType", error.getClass().getName())
                    .log("发布版本数据库迁移检查失败");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "发布数据库检查不可用", error);
        }

        List<String> applied = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        boolean foundRequiredDefinition = false;
        boolean pastRequired = false;

        for (MigrationProbe probe : BASELINE_MIGRATIONS) {
            boolean present = probe.check().test(objects);
            if (present) {
                applied.add(probe.id());
            } else if (!pastRequired) {
                missing.add(probe.id());
            }
            if (probe.id().equals(requiredMigration)) {
                foundRequiredDefinition = true;
                pastRequired = true;
            }
        }
        if (!foundRequiredDefinition) {
            missing.add(requiredMigration);
        }

        String latestDetected = applied.isEmpty() ? "NONE" : applied.getLast();
        boolean compatible = missing.isEmpty();
        if (!compatible) {
            log.atWarn().addKeyValue("event", "release_schema_incompatible")
                    .addKeyValue("releaseLabel", releaseLabel)
                    .addKeyValue("gitSha", gitSha)
                    .addKeyValue("requiredMigration", requiredMigration)
                    .addKeyValue("missingMigrations", missing)
                    .log("当前应用版本与数据库迁移状态不兼容");
        }

        return new DatabaseMigrationState(requiredMigration, latestDetected, List.copyOf(applied), List.copyOf(missing), compatible);
    }

    private record SchemaObjects(Set<String> tables, Set<String> columns, Set<String> indexes) {
    }

    private record MigrationProbe(String id, Predicate<SchemaObjects> check) {
    }

    public record ApplicationVersion(
            String artifact,
            String version,
            Instant buildTime,
            String gitSha,
            String releaseLabel,
            String instanceId) {
    }

    public record DatabaseMigrationState(
            String requiredMigration,
            String latestDetectedMigration,
            List<String> appliedMigrations,
            List<String> missingMigrations,
            boolean compatible) {
    }

    public record FeatureFlags(
            String searchMode,
            boolean seckillEnabled,
            boolean productCacheEnabled,
            boolean orderRateLimitEnabled,
            boolean orderMessageEnabled,
            boolean orderMessageMonitorEnabled,
            boolean agentEnabled) {
    }

    public record ReleaseSnapshot(
            LocalDateTime collectedAt,
            ApplicationVersion application,
            DatabaseMigrationState database,
            FeatureFlags features) {
    }
}
