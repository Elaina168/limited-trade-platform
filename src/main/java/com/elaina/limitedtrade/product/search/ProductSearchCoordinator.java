package com.elaina.limitedtrade.product.search;

import com.elaina.limitedtrade.product.ProductChangedEvent;
import com.elaina.limitedtrade.product.ProductSearchCriteria;
import com.elaina.limitedtrade.product.ProductSearchPage;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
    private final ProductSearchSyncMapper pending;
    private final ProductSearchIndexSync indexSync;
    private final ThreadPoolTaskScheduler scheduler;
    private final AtomicLong circuitOpenUntilNanos = new AtomicLong(0);
    private final AtomicBoolean bootstrapScheduled = new AtomicBoolean();
    private volatile boolean startupSyncPending;

    public ProductSearchCoordinator(SearchProperties properties,
                                    MysqlProductSearchGateway mysqlGateway,
                                    ElasticsearchProductSearchGateway esGateway,
                                    ProductSearchSyncMapper pending,
                                    ProductSearchIndexSync indexSync,
                                    @Qualifier("productSearchTaskScheduler") ThreadPoolTaskScheduler scheduler) {
        this.properties = properties;
        this.mysqlGateway = mysqlGateway;
        this.esGateway = esGateway;
        this.pending = pending;
        this.indexSync = indexSync;
        this.scheduler = scheduler;
        this.startupSyncPending = properties.elasticsearch().autoSyncOnStartup();
    }

    public ProductSearchPage search(ProductSearchCriteria criteria) {
        if (properties.mode() != SearchProperties.Mode.ES) {
            return mysqlGateway.search(criteria);
        }
        if (!isIndexReady(false)) {
            return mysqlGateway.search(criteria).asDegraded();
        }
        try {
            return esGateway.search(criteria);
        } catch (RuntimeException error) {
            tripCircuit(error, "search");
            return mysqlGateway.search(criteria).asDegraded();
        }
    }

    public List<String> suggest(String prefix, int limit) {
        if (properties.mode() != SearchProperties.Mode.ES) {
            return mysqlGateway.suggest(prefix, limit);
        }
        if (!isIndexReady(false)) {
            return mysqlGateway.suggest(prefix, limit);
        }
        try {
            return esGateway.suggest(prefix, limit);
        } catch (RuntimeException error) {
            tripCircuit(error, "suggest");
            return mysqlGateway.suggest(prefix, limit);
        }
    }

    public int reindexAll() {
        int count = indexSync.reindexAll();
        startupSyncPending = false;
        circuitOpenUntilNanos.set(0);
        return count;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${app.search.elasticsearch.sync-delay-ms:1000}", scheduler = "productSearchTaskScheduler")
    public void syncPending() {
        if (properties.mode() == SearchProperties.Mode.ES) {
            isIndexReady(true);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void enqueueProductChanges(ProductChangedEvent event) {
        if (properties.mode() == SearchProperties.Mode.ES && !event.productIds().isEmpty()) {
            pending.enqueue(event.productIds().stream().distinct().sorted().toList());
        }
    }

    private boolean isIndexReady(boolean allowFullInitialization) {
        long openUntil = circuitOpenUntilNanos.get();
        if (openUntil != 0 && System.nanoTime() - openUntil < 0) {
            return false;
        }
        try {
            if (startupSyncPending) {
                // 查询只触发后台探测，全量初始化保留原有超时，不阻塞搜索请求。
                if (!allowFullInitialization) {
                    if (bootstrapScheduled.compareAndSet(false, true)) {
                        scheduler.execute(() -> {
                            try {
                                isIndexReady(true);
                            } finally {
                                bootstrapScheduled.set(false);
                            }
                        });
                    }
                    return false;
                }
                if (!indexSync.initializeIndex()) {
                    return false;
                }
                startupSyncPending = false;
            }
            if (!pending.hasPending()) {
                return true;
            }
            indexSync.drainBatch();
            return !pending.hasPending();
        } catch (RuntimeException error) {
            tripCircuit(error, "incremental_sync");
            return false;
        }
    }

    private void tripCircuit(RuntimeException error, String stage) {
        long cooldownNanos = TimeUnit.SECONDS.toNanos(properties.elasticsearch().failureCooldownSeconds());
        circuitOpenUntilNanos.set(System.nanoTime() + cooldownNanos);
        esGateway.invalidateIndexVerification();
        startupSyncPending = properties.elasticsearch().autoSyncOnStartup();
        log.atWarn().addKeyValue("event", "elasticsearch_search_degraded")
                .addKeyValue("stage", stage)
                .setCause(error)
                .log("Elasticsearch不可用，已自动熔断降级至MySQL商品检索");
    }
}
