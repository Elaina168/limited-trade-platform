package com.elaina.limitedtrade.product.search;

import com.elaina.limitedtrade.product.ProductChangedEvent;
import com.elaina.limitedtrade.product.ProductMapper;
import com.elaina.limitedtrade.product.ProductSearchCriteria;
import com.elaina.limitedtrade.product.ProductSearchItem;
import com.elaina.limitedtrade.product.ProductSearchPage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@EnableConfigurationProperties(SearchProperties.class)
public class ProductSearchCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchCoordinator.class);

    private final SearchProperties properties;
    private final MysqlProductSearchGateway mysqlGateway;
    private final ElasticsearchProductSearchGateway esGateway;
    private final ProductMapper productMapper;
    private final AtomicLong circuitOpenUntilNanos = new AtomicLong(0);

    public ProductSearchCoordinator(SearchProperties properties,
                                    MysqlProductSearchGateway mysqlGateway,
                                    ElasticsearchProductSearchGateway esGateway,
                                    ProductMapper productMapper) {
        this.properties = properties;
        this.mysqlGateway = mysqlGateway;
        this.esGateway = esGateway;
        this.productMapper = productMapper;
    }

    public ProductSearchPage search(ProductSearchCriteria criteria) {
        if (properties.mode() != SearchProperties.Mode.ES) {
            return mysqlGateway.search(criteria);
        }
        long openUntil = circuitOpenUntilNanos.get();
        if (openUntil != 0 && System.nanoTime() - openUntil < 0) {
            return mysqlGateway.search(criteria).asDegraded();
        }
        try {
            ProductSearchPage page = esGateway.search(criteria);
            circuitOpenUntilNanos.set(0);
            return page;
        } catch (RuntimeException error) {
            tripCircuit(error, "search");
            return mysqlGateway.search(criteria).asDegraded();
        }
    }

    public List<String> suggest(String prefix, int limit) {
        if (properties.mode() != SearchProperties.Mode.ES) {
            return mysqlGateway.suggest(prefix, limit);
        }
        long openUntil = circuitOpenUntilNanos.get();
        if (openUntil != 0 && System.nanoTime() - openUntil < 0) {
            return mysqlGateway.suggest(prefix, limit);
        }
        try {
            List<String> suggestions = esGateway.suggest(prefix, limit);
            circuitOpenUntilNanos.set(0);
            return suggestions;
        } catch (RuntimeException error) {
            tripCircuit(error, "suggest");
            return mysqlGateway.suggest(prefix, limit);
        }
    }

    public int reindexAll() {
        List<ProductSearchItem> onSale = productMapper.findAllOnSaleSearchItems();
        int count = esGateway.reindexAll(onSale);
        circuitOpenUntilNanos.set(0);
        return count;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (properties.mode() != SearchProperties.Mode.ES
                || !Boolean.TRUE.equals(properties.elasticsearch().autoSyncOnStartup())) {
            return;
        }
        try {
            boolean created = esGateway.ensureIndex();
            if (created || esGateway.countDocuments() == 0) {
                List<ProductSearchItem> onSale = productMapper.findAllOnSaleSearchItems();
                if (!onSale.isEmpty()) {
                    esGateway.bulkUpsert(onSale, List.of(), true);
                }
            }
        } catch (RuntimeException error) {
            tripCircuit(error, "startup_sync");
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProductChanged(ProductChangedEvent event) {
        if (properties.mode() != SearchProperties.Mode.ES || event.productIds() == null || event.productIds().isEmpty()) {
            return;
        }
        try {
            List<Long> ids = event.productIds().stream().distinct().toList();
            List<ProductSearchItem> existing = productMapper.findSearchItemsByIds(ids);
            Set<Long> foundIds = new HashSet<>();
            List<ProductSearchItem> upserts = new ArrayList<>();
            List<Long> deletes = new ArrayList<>();
            for (ProductSearchItem item : existing) {
                foundIds.add(item.id());
                if ("ON_SALE".equals(item.status())) {
                    upserts.add(item);
                } else {
                    deletes.add(item.id());
                }
            }
            for (Long id : ids) {
                if (!foundIds.contains(id)) {
                    deletes.add(id);
                }
            }
            esGateway.bulkUpsert(upserts, deletes, true);
        } catch (RuntimeException error) {
            log.atWarn().addKeyValue("event", "elasticsearch_incremental_sync_failed")
                    .addKeyValue("productIds", event.productIds())
                    .setCause(error)
                    .log("商品变更同步Elasticsearch失败，等待恢复后重同步");
        }
    }

    private void tripCircuit(RuntimeException error, String stage) {
        long cooldownNanos = TimeUnit.SECONDS.toNanos(properties.elasticsearch().failureCooldownSeconds());
        circuitOpenUntilNanos.set(System.nanoTime() + cooldownNanos);
        log.atWarn().addKeyValue("event", "elasticsearch_search_degraded")
                .addKeyValue("stage", stage)
                .setCause(error)
                .log("Elasticsearch不可用，已自动熔断降级至MySQL商品检索");
    }
}
