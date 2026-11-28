package com.elaina.limitedtrade.product.search;

import com.elaina.limitedtrade.product.ProductMapper;
import com.elaina.limitedtrade.product.ProductSearchItem;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductSearchIndexSync {
    private static final int BATCH_SIZE = 100;

    private final ProductSearchSyncMapper pending;
    private final ProductMapper products;
    private final ElasticsearchProductSearchGateway es;

    public ProductSearchIndexSync(ProductSearchSyncMapper pending, ProductMapper products,
                                  ElasticsearchProductSearchGateway es) {
        this.pending = pending;
        this.products = products;
        this.es = es;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void drainBatch() {
        // 只锁同步任务的互斥行，远程请求期间仍可写入商品和待同步记录。
        if (pending.tryLockWorker() == null) {
            return;
        }
        List<ProductSearchChange> changes = pending.findPending(BATCH_SIZE);
        if (changes.isEmpty()) {
            return;
        }
        List<Long> ids = changes.stream().map(ProductSearchChange::productId).toList();
        Map<Long, ProductSearchItem> onSale = products.findSearchItemsByIds(ids).stream()
                .filter(item -> "ON_SALE".equals(item.status()))
                .collect(Collectors.toMap(ProductSearchItem::id, Function.identity()));
        List<Long> deletes = ids.stream().filter(id -> !onSale.containsKey(id)).toList();
        es.bulkIncrementalUpsert(List.copyOf(onSale.values()), deletes);
        // 商品在同步中再次修改时，旧标识不会删除新记录。
        pending.acknowledge(changes);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean initializeIndex() {
        if (pending.tryLockWorker() == null) {
            return false;
        }
        if (es.ensureIndex() || es.countDocuments() == 0) {
            List<ProductSearchItem> onSale = products.findAllOnSaleSearchItems();
            if (!onSale.isEmpty()) {
                es.bulkUpsert(onSale, List.of(), true);
            }
        }
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int reindexAll() {
        pending.lockWorker();
        return es.reindexAll(products.findAllOnSaleSearchItems());
    }
}
