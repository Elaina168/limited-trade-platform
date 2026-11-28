package com.elaina.limitedtrade.system;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.release")
public record ReleaseProperties(
        String gitSha,
        String label,
        String requiredMigration) {

    public ReleaseProperties {
        gitSha = gitSha == null ? "" : gitSha.strip();
        label = label == null || label.isBlank() ? "local" : label.strip();
        requiredMigration = requiredMigration == null || requiredMigration.isBlank()
                ? "018_product_search_sync"
                : requiredMigration.strip();
    }
}
