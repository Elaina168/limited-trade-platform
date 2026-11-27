package com.elaina.limitedtrade.product.search;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.search")
public record SearchProperties(
        Mode mode,
        Elasticsearch elasticsearch) {

    public SearchProperties {
        if (mode == null) {
            mode = Mode.MYSQL;
        }
        if (elasticsearch == null) {
            elasticsearch = new Elasticsearch("http://127.0.0.1:9200", "limited_products_v1",
                    Duration.ofSeconds(1), Duration.ofSeconds(2), 15, true, null, null);
        }
    }

    public enum Mode {
        MYSQL,
        ES
    }

    public record Elasticsearch(
            String baseUrl,
            String indexName,
            Duration connectTimeout,
            Duration readTimeout,
            int failureCooldownSeconds,
            Boolean autoSyncOnStartup,
            String username,
            String password) {
        public Elasticsearch {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://127.0.0.1:9200";
            }
            if (indexName == null || indexName.isBlank()) {
                indexName = "limited_products_v1";
            }
            if (connectTimeout == null) {
                connectTimeout = Duration.ofSeconds(1);
            }
            if (readTimeout == null) {
                readTimeout = Duration.ofSeconds(2);
            }
            if (failureCooldownSeconds <= 0) {
                failureCooldownSeconds = 15;
            }
            if (autoSyncOnStartup == null) {
                autoSyncOnStartup = true;
            }
        }
    }
}
