package com.elaina.limitedtrade.product.search;

import com.elaina.limitedtrade.category.Category;
import com.elaina.limitedtrade.category.CategoryMapper;
import com.elaina.limitedtrade.product.CategoryFacet;
import com.elaina.limitedtrade.product.ProductMapper;
import com.elaina.limitedtrade.product.ProductSearchCriteria;
import com.elaina.limitedtrade.product.ProductSearchItem;
import com.elaina.limitedtrade.product.ProductSearchPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ElasticsearchSearchFallbackTest {

    @Test
    void searchReturnsMysqlWhileAnIndexInitializationIsScheduled() {
        var props = new SearchProperties(SearchProperties.Mode.ES, null);
        var pending = Mockito.mock(ProductSearchSyncMapper.class);
        var sync = Mockito.mock(ProductSearchIndexSync.class);
        var mysql = Mockito.mock(MysqlProductSearchGateway.class);
        var es = Mockito.mock(ElasticsearchProductSearchGateway.class);
        var scheduler = Mockito.mock(ThreadPoolTaskScheduler.class);
        var coordinator = new ProductSearchCoordinator(props, mysql, es, pending, sync, scheduler);
        var criteria = new ProductSearchCriteria(1, 12, null, null, null, null, false, "relevance");
        when(mysql.search(criteria))
                .thenReturn(new ProductSearchPage(1, 12, 1, List.of(), List.of(), "mysql", false));

        assertTrue(coordinator.search(criteria).degraded());
        assertTrue(coordinator.search(criteria).degraded());
        verify(scheduler).execute(any(Runnable.class));
        verify(sync, never()).initializeIndex();
        verify(es, never()).search(criteria);
    }

    @Test
    void aFailedBulkRemainsPendingAndRecoveryAcknowledgesOnlyTheReadVersion() {
        ProductSearchSyncMapper pending = Mockito.mock(ProductSearchSyncMapper.class);
        ProductMapper products = Mockito.mock(ProductMapper.class);
        ElasticsearchProductSearchGateway es = Mockito.mock(ElasticsearchProductSearchGateway.class);
        var sync = new ProductSearchIndexSync(pending, products, es);
        var previous = new ProductSearchChange(10L, "previous");
        var latest = new ProductSearchChange(10L, "latest");
        when(pending.tryLockWorker()).thenReturn(0L);
        when(pending.findPending(anyInt())).thenReturn(List.of(previous), List.of(latest));
        var offSale = new ProductSearchItem(10L, "键盘", new BigDecimal("199.00"), 5, "OFF_SALE", null, null);
        when(products.findSearchItemsByIds(List.of(10L))).thenReturn(List.of(offSale));
        doThrow(new IllegalStateException("ES unavailable")).doNothing()
                .when(es).bulkIncrementalUpsert(List.of(), List.of(10L));

        assertThrows(IllegalStateException.class, sync::drainBatch);
        verify(pending, never()).acknowledge(any());
        sync.drainBatch();
        verify(pending).acknowledge(List.of(latest));
        verify(pending, never()).acknowledge(List.of(previous));
    }

    @Test
    void aReachableElasticsearchIsNotUsedBeforePendingChangesAreApplied() {
        var props = new SearchProperties(SearchProperties.Mode.ES,
                new SearchProperties.Elasticsearch(null, null, null, null, 5, false, null, null));
        ProductSearchSyncMapper pending = Mockito.mock(ProductSearchSyncMapper.class);
        ProductSearchIndexSync sync = Mockito.mock(ProductSearchIndexSync.class);
        MysqlProductSearchGateway mysql = Mockito.mock(MysqlProductSearchGateway.class);
        ElasticsearchProductSearchGateway es = Mockito.mock(ElasticsearchProductSearchGateway.class);
        var coordinator = new ProductSearchCoordinator(props, mysql, es, pending, sync,
                Mockito.mock(ThreadPoolTaskScheduler.class));
        var criteria = new ProductSearchCriteria(1, 12, null, null, null, null, false, "relevance");
        var mysqlPage = new ProductSearchPage(1, 12, 1, List.of(), List.of(), "mysql", false);
        var esPage = new ProductSearchPage(1, 12, 1, List.of(), List.of(), "elasticsearch", false);
        when(mysql.search(criteria)).thenReturn(mysqlPage);
        when(es.search(criteria)).thenReturn(esPage);
        when(pending.hasPending()).thenReturn(true, true, true, false);

        assertTrue(coordinator.search(criteria).degraded());
        verify(es, never()).search(criteria);
        assertEquals(esPage, coordinator.search(criteria));
    }

    @Test
    void elasticsearchGatewayParsesHighlightsAndFacetsAndFallsBackToMysqlOnFailure() throws Exception {
        AtomicInteger esSearchCalls = new AtomicInteger();
        AtomicInteger failMode = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_cluster/health", exchange -> {
            byte[] bytes = "{\"status\":\"green\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/limited_products_v1", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.createContext("/limited_products_v1/_search", exchange -> {
            esSearchCalls.incrementAndGet();
            if (failMode.get() == 1) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            String json = """
                    {
                      "hits": {
                        "total": { "value": 1 },
                        "hits": [{
                          "_source": {
                            "id": 101,
                            "name": "无线<游戏>键盘",
                            "price": 199.00,
                            "stock": 5,
                            "status": "ON_SALE",
                            "categoryId": 10,
                            "categoryName": "外设"
                          },
                          "highlight": {
                            "name": ["无线<游戏>\\uE000键盘\\uE001"]
                          }
                        }]
                      },
                      "aggregations": {
                        "by_category": {
                          "buckets": [{ "key": 10, "doc_count": 1 }]
                        }
                      }
                    }
                    """;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            SearchProperties props = new SearchProperties(SearchProperties.Mode.ES,
                    new SearchProperties.Elasticsearch("http://127.0.0.1:" + port, "limited_products_v1",
                            Duration.ofSeconds(1), Duration.ofSeconds(1), 5, false, null, null));
            CategoryMapper categoryMapper = Mockito.mock(CategoryMapper.class);
            Category cat = new Category();
            cat.setId(10L);
            cat.setName("外设");
            when(categoryMapper.findAll()).thenReturn(List.of(cat));

            ProductMapper productMapper = Mockito.mock(ProductMapper.class);
            when(productMapper.countFilteredSearchProducts(any(), any(), any(), any(), anyBoolean())).thenReturn(1L);
            when(productMapper.searchFilteredProducts(any(), any(), any(), any(), anyBoolean(), any(), anyLong(), anyInt()))
                    .thenReturn(List.of(new ProductSearchItem(101L, "无线<游戏>键盘", new BigDecimal("199.00"), 5, "ON_SALE", 10L, "外设")));
            when(productMapper.aggregateSearchCategories(any(), any(), any(), anyBoolean()))
                    .thenReturn(List.of(new CategoryFacet(10L, "外设", 1L)));

            MysqlProductSearchGateway mysqlGateway = new MysqlProductSearchGateway(productMapper);
            ElasticsearchProductSearchGateway esGateway = new ElasticsearchProductSearchGateway(props, new ObjectMapper(), categoryMapper);
            ProductSearchCoordinator coordinator = new ProductSearchCoordinator(props, mysqlGateway, esGateway,
                    Mockito.mock(ProductSearchSyncMapper.class), Mockito.mock(ProductSearchIndexSync.class),
                    Mockito.mock(ThreadPoolTaskScheduler.class));

            ProductSearchPage okPage = coordinator.search(new ProductSearchCriteria(1, 12, null, "键盘", null, null, false, "relevance"));
            assertEquals("elasticsearch", okPage.engine());
            assertFalse(okPage.degraded());
            assertEquals("无线&lt;游戏&gt;<mark>键盘</mark>", okPage.items().getFirst().highlightedName());
            assertEquals(1, okPage.categoryFacets().size());
            assertEquals(10L, okPage.categoryFacets().getFirst().categoryId());

            failMode.set(1);
            ProductSearchPage degradedPage = coordinator.search(new ProductSearchCriteria(1, 12, null, "键盘", null, null, false, "relevance"));
            assertEquals("mysql", degradedPage.engine());
            assertTrue(degradedPage.degraded());
            assertEquals("无线&lt;游戏&gt;<mark>键盘</mark>", degradedPage.items().getFirst().highlightedName());

            int callsBeforeCooldownHit = esSearchCalls.get();
            ProductSearchPage cooldownPage = coordinator.search(new ProductSearchCriteria(1, 12, null, "键盘", null, null, false, "relevance"));
            assertTrue(cooldownPage.degraded());
            assertEquals(callsBeforeCooldownHit, esSearchCalls.get());
        } finally {
            server.stop(0);
        }
    }
}
