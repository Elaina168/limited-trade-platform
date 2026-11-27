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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

class ElasticsearchSearchFallbackTest {

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
            ProductSearchCoordinator coordinator = new ProductSearchCoordinator(props, mysqlGateway, esGateway, productMapper);

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
