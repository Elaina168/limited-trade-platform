package com.elaina.limitedtrade.product.search;

import com.elaina.limitedtrade.category.Category;
import com.elaina.limitedtrade.category.CategoryMapper;
import com.elaina.limitedtrade.product.CategoryFacet;
import com.elaina.limitedtrade.product.ProductSearchCriteria;
import com.elaina.limitedtrade.product.ProductSearchItem;
import com.elaina.limitedtrade.product.ProductSearchPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

@Component
public class ElasticsearchProductSearchGateway implements ProductSearchGateway {

    private static final String PRE_MARKER = "\uE000";
    private static final String POST_MARKER = "\uE001";

    private final SearchProperties properties;
    private final ObjectMapper mapper;
    private final CategoryMapper categoryMapper;
    private final RestClient restClient;
    private final RestClient adminRestClient;
    private volatile boolean indexVerified;
    private volatile Map<Long, String> cachedCategoryNames = Map.of();
    private volatile long categoryCacheExpiresAtNanos;

    public ElasticsearchProductSearchGateway(SearchProperties properties,
                                             ObjectMapper mapper,
                                             CategoryMapper categoryMapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.categoryMapper = categoryMapper;
        var es = properties.elasticsearch();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(es.connectTimeout())
                .build();
        JdkClientHttpRequestFactory queryFactory = new JdkClientHttpRequestFactory(httpClient);
        queryFactory.setReadTimeout(es.readTimeout());
        JdkClientHttpRequestFactory bulkFactory = new JdkClientHttpRequestFactory(httpClient);
        bulkFactory.setReadTimeout(java.time.Duration.ofSeconds(30));

        RestClient.Builder queryBuilder = RestClient.builder().baseUrl(es.baseUrl()).requestFactory(queryFactory);
        RestClient.Builder bulkBuilder = RestClient.builder().baseUrl(es.baseUrl()).requestFactory(bulkFactory);
        if (es.username() != null && !es.username().isBlank()) {
            String basic = Base64.getEncoder().encodeToString(
                    (es.username() + ":" + (es.password() == null ? "" : es.password())).getBytes(StandardCharsets.UTF_8));
            queryBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic);
            bulkBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic);
        }
        this.restClient = queryBuilder.build();
        this.adminRestClient = bulkBuilder.build();
    }

    public synchronized boolean ensureIndex() {
        if (indexVerified) {
            return false;
        }
        String index = properties.elasticsearch().indexName();
        try {
            adminRestClient.head().uri("/{index}", index).retrieve().toBodilessEntity();
            adminRestClient.get()
                    .uri("/_cluster/health/{index}?wait_for_status=yellow&timeout=10s", index)
                    .retrieve()
                    .toBodilessEntity();
            indexVerified = true;
            return false;
        } catch (HttpClientErrorException.NotFound notFound) {
            boolean ikAvailable = detectIkPlugin();
            String mappingJson = buildIndexSettingsAndMappings(ikAvailable);
            try {
                adminRestClient.put().uri("/{index}?wait_for_active_shards=1", index)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(mappingJson.getBytes(StandardCharsets.UTF_8))
                        .retrieve()
                        .toBodilessEntity();
            } catch (HttpClientErrorException.BadRequest alreadyExists) {
                if (!alreadyExists.getResponseBodyAsString().contains("resource_already_exists_exception")) {
                    throw alreadyExists;
                }
            }
            adminRestClient.get()
                    .uri("/_cluster/health/{index}?wait_for_status=yellow&timeout=10s", index)
                    .retrieve()
                    .toBodilessEntity();
            indexVerified = true;
            return true;
        }
    }

    private boolean detectIkPlugin() {
        try {
            String plugins = restClient.get().uri("/_cat/plugins?format=json").retrieve().body(String.class);
            return plugins != null && plugins.contains("analysis-ik");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String buildIndexSettingsAndMappings(boolean ikAvailable) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode settings = root.putObject("settings");
        settings.put("number_of_shards", 1);
        settings.put("number_of_replicas", 0);
        settings.put("max_ngram_diff", 2);

        ObjectNode analysis = settings.putObject("analysis");
        ObjectNode tokenizer = analysis.putObject("tokenizer");
        ObjectNode zhNgram = tokenizer.putObject("zh_ngram_tokenizer");
        zhNgram.put("type", "ngram")
                .put("min_gram", 1)
                .put("max_gram", 2);
        zhNgram.putArray("token_chars").add("letter").add("digit");
        tokenizer.putObject("prefix_edge_tokenizer")
                .put("type", "edge_ngram")
                .put("min_gram", 1)
                .put("max_gram", 20);

        ObjectNode analyzer = analysis.putObject("analyzer");
        ObjectNode ngramAnalyzer = analyzer.putObject("zh_ngram_analyzer");
        ngramAnalyzer.put("type", "custom");
        ngramAnalyzer.put("tokenizer", "zh_ngram_tokenizer");
        ngramAnalyzer.putArray("filter").add("lowercase");

        ObjectNode prefixAnalyzer = analyzer.putObject("prefix_analyzer");
        prefixAnalyzer.put("type", "custom");
        prefixAnalyzer.put("tokenizer", "prefix_edge_tokenizer");
        prefixAnalyzer.putArray("filter").add("lowercase");

        String primaryAnalyzer = ikAvailable ? "ik_max_word" : "zh_ngram_analyzer";
        String searchAnalyzer = ikAvailable ? "ik_smart" : "zh_ngram_analyzer";

        ObjectNode propertiesNode = root.putObject("mappings").putObject("properties");
        propertiesNode.putObject("id").put("type", "long");
        ObjectNode nameField = propertiesNode.putObject("name");
        nameField.put("type", "text");
        nameField.put("analyzer", primaryAnalyzer);
        nameField.put("search_analyzer", searchAnalyzer);
        ObjectNode nameSub = nameField.putObject("fields");
        nameSub.putObject("raw").put("type", "keyword");
        nameSub.putObject("ngram").put("type", "text")
                .put("analyzer", "zh_ngram_analyzer")
                .put("search_analyzer", "zh_ngram_analyzer");
        nameSub.putObject("prefix").put("type", "text")
                .put("analyzer", "prefix_analyzer")
                .put("search_analyzer", "standard");

        propertiesNode.putObject("price").put("type", "scaled_float").put("scaling_factor", 100);
        propertiesNode.putObject("stock").put("type", "integer");
        propertiesNode.putObject("status").put("type", "keyword");
        propertiesNode.putObject("categoryId").put("type", "long");
        propertiesNode.putObject("categoryName").put("type", "keyword");
        return root.toString();
    }

    public int reindexAll(List<ProductSearchItem> onSaleProducts) {
        indexVerified = false;
        String index = properties.elasticsearch().indexName();
        try {
            adminRestClient.delete().uri("/{index}", index).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound ignored) {
        }
        ensureIndex();
        bulkUpsert(onSaleProducts, List.of(), true);
        return onSaleProducts.size();
    }

    public void bulkUpsert(List<ProductSearchItem> upserts, List<Long> deleteIds, boolean refresh) {
        ensureIndex();
        if (upserts.isEmpty() && deleteIds.isEmpty()) {
            return;
        }
        int batchSize = 1000;
        for (int start = 0; start < upserts.size(); start += batchSize) {
            int end = Math.min(upserts.size(), start + batchSize);
            boolean lastUpsertBatch = end == upserts.size() && deleteIds.isEmpty();
            sendBulkChunk(upserts.subList(start, end), List.of(), refresh && lastUpsertBatch);
        }
        if (!deleteIds.isEmpty()) {
            sendBulkChunk(List.of(), deleteIds, refresh);
        }
    }

    private void sendBulkChunk(List<ProductSearchItem> upserts, List<Long> deleteIds, boolean refresh) {
        String index = properties.elasticsearch().indexName();
        StringBuilder ndjson = new StringBuilder((upserts.size() + deleteIds.size()) * 160);
        try {
            for (ProductSearchItem item : upserts) {
                if (!"ON_SALE".equals(item.status())) {
                    ndjson.append("{\"delete\":{\"_index\":\"").append(index)
                            .append("\",\"_id\":\"").append(item.id()).append("\"}}\n");
                    continue;
                }
                ndjson.append("{\"index\":{\"_index\":\"").append(index)
                        .append("\",\"_id\":\"").append(item.id()).append("\"}}\n");
                ObjectNode doc = mapper.createObjectNode();
                doc.put("id", item.id());
                doc.put("name", item.name());
                doc.put("price", item.price());
                doc.put("stock", item.stock());
                doc.put("status", item.status());
                if (item.categoryId() == null) {
                    doc.putNull("categoryId");
                } else {
                    doc.put("categoryId", item.categoryId());
                }
                if (item.categoryName() == null) {
                    doc.putNull("categoryName");
                } else {
                    doc.put("categoryName", item.categoryName());
                }
                ndjson.append(mapper.writeValueAsString(doc)).append('\n');
            }
            for (Long id : deleteIds) {
                ndjson.append("{\"delete\":{\"_index\":\"").append(index)
                        .append("\",\"_id\":\"").append(id).append("\"}}\n");
            }
        } catch (Exception error) {
            throw new IllegalStateException("构建ES批量索引载荷失败", error);
        }
        String uri = refresh ? "/_bulk?refresh=true" : "/_bulk";
        byte[] bulkBytes = adminRestClient.post().uri(uri)
                .contentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"))
                .body(ndjson.toString().getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .body(byte[].class);
        String bulkResp = bulkBytes == null ? null : new String(bulkBytes, StandardCharsets.UTF_8);
        if (bulkResp != null && bulkResp.contains("\"errors\":true")) {
            throw new IllegalStateException("ES批量索引返回部分错误: "
                    + bulkResp.substring(0, Math.min(400, bulkResp.length())));
        }
    }

    public long countDocuments() {
        ensureIndex();
        String index = properties.elasticsearch().indexName();
        byte[] raw = adminRestClient.get().uri("/{index}/_count", index).retrieve().body(byte[].class);
        try {
            return raw == null ? 0 : mapper.readTree(raw).path("count").asLong(0);
        } catch (Exception error) {
            return 0;
        }
    }

    @Override
    public ProductSearchPage search(ProductSearchCriteria criteria) {
        ensureIndex();
        String index = properties.elasticsearch().indexName();
        String keyword = criteria.normalizedKeyword();
        int size = criteria.size();
        long fromLong = (long) (criteria.page() - 1) * size;
        // 超出ES默认分页深度的极端页码返回精确总数与空列表。
        boolean outOfWindow = fromLong >= 10_000;

        ObjectNode dsl = mapper.createObjectNode();
        dsl.put("track_total_hits", true);
        dsl.put("from", outOfWindow ? 0 : (int) fromLong);
        dsl.put("size", outOfWindow ? 0 : size);

        ObjectNode bool = dsl.putObject("query").putObject("bool");
        ArrayNode filters = bool.putArray("filter");
        filters.addObject().putObject("term").put("status", "ON_SALE");
        if (criteria.inStockOnly()) {
            filters.addObject().putObject("range").putObject("stock").put("gt", 0);
        }
        if (criteria.minPrice() != null || criteria.maxPrice() != null) {
            ObjectNode priceRange = filters.addObject().putObject("range").putObject("price");
            if (criteria.minPrice() != null) {
                priceRange.put("gte", criteria.minPrice());
            }
            if (criteria.maxPrice() != null) {
                priceRange.put("lte", criteria.maxPrice());
            }
        }
        if (keyword != null) {
            ArrayNode should = bool.putArray("must").addObject().putObject("bool").putArray("should");
            should.addObject().putObject("match_phrase").putObject("name")
                    .put("query", keyword).put("boost", 4.0);
            should.addObject().putObject("match").putObject("name")
                    .put("query", keyword).put("operator", "and").put("boost", 2.5);
            if (containsNonAlphanumeric(keyword)) {
                should.addObject().putObject("wildcard").putObject("name.raw")
                        .put("value", "*" + escapeWildcard(keyword) + "*")
                        .put("case_insensitive", true)
                        .put("boost", 1.5);
            }
        }

        if (criteria.categoryId() != null) {
            dsl.putObject("post_filter").putObject("term").put("categoryId", criteria.categoryId());
        }

        ObjectNode aggs = dsl.putObject("aggs");
        ObjectNode catTerms = aggs.putObject("by_category").putObject("terms");
        catTerms.put("field", "categoryId");
        catTerms.put("size", 100);
        catTerms.put("missing", -1);

        if (keyword != null && !outOfWindow) {
            ObjectNode highlight = dsl.putObject("highlight");
            highlight.putArray("pre_tags").add(PRE_MARKER);
            highlight.putArray("post_tags").add(POST_MARKER);
            ObjectNode fields = highlight.putObject("fields");
            fields.putObject("name").put("number_of_fragments", 0);
        }

        ArrayNode sortArr = dsl.putArray("sort");
        String sortMode = criteria.normalizedSort();
        if ("price_asc".equals(sortMode)) {
            sortArr.addObject().putObject("price").put("order", "asc");
            sortArr.addObject().putObject("id").put("order", "asc");
        } else if ("price_desc".equals(sortMode)) {
            sortArr.addObject().putObject("price").put("order", "desc");
            sortArr.addObject().putObject("id").put("order", "asc");
        } else if ("relevance".equals(sortMode) && keyword != null) {
            sortArr.addObject().putObject("_score").put("order", "desc");
            sortArr.addObject().putObject("id").put("order", "asc");
        } else {
            sortArr.addObject().putObject("id").put("order", "asc");
        }

        byte[] rawResponse = restClient.post().uri("/{index}/_search", index)
                .contentType(MediaType.APPLICATION_JSON)
                .body(dsl.toString().getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .body(byte[].class);

        try {
            JsonNode root = mapper.readTree(rawResponse);
            JsonNode hitsNode = root.path("hits");
            long total = criteria.categoryId() == null
                    ? hitsNode.path("total").path("value").asLong(0)
                    : countPostFilteredTotal(root, hitsNode, criteria.categoryId());

            List<ProductSearchItem> items = new ArrayList<>();
            if (!outOfWindow) {
                for (JsonNode hit : hitsNode.path("hits")) {
                    JsonNode src = hit.path("_source");
                    String name = src.path("name").asText();
                    String highlighted = extractSafeHighlight(hit.path("highlight"), name, keyword);
                    Long catId = src.path("categoryId").isNull() || src.path("categoryId").isMissingNode()
                            ? null : src.path("categoryId").asLong();
                    String catName = src.path("categoryName").isNull() || src.path("categoryName").isMissingNode()
                            ? null : src.path("categoryName").asText();
                    items.add(new ProductSearchItem(
                            src.path("id").asLong(),
                            name,
                            new BigDecimal(src.path("price").asText("0.00")),
                            src.path("stock").asInt(),
                            src.path("status").asText("ON_SALE"),
                            catId,
                            catName,
                            highlighted));
                }
            }

            Map<Long, String> categoryNames = loadCategoryNameMap();
            List<CategoryFacet> facets = new ArrayList<>();
            for (JsonNode bucket : root.path("aggregations").path("by_category").path("buckets")) {
                long key = bucket.path("key").asLong();
                long docCount = bucket.path("doc_count").asLong();
                if (docCount <= 0) {
                    continue;
                }
                Long facetCatId = key == -1L ? null : key;
                String facetName = facetCatId == null ? null : categoryNames.get(facetCatId);
                facets.add(new CategoryFacet(facetCatId, facetName, docCount));
            }
            return new ProductSearchPage(criteria.page(), size, total, items, facets, "elasticsearch", false);
        } catch (Exception parseError) {
            throw new IllegalStateException("解析ES搜索响应失败", parseError);
        }
    }

    private long countPostFilteredTotal(JsonNode root, JsonNode hitsNode, Long categoryId) {
        // post_filter下hits.total反映全部匹配数，指定categoryId时优先从by_category分桶取精确文档数。
        for (JsonNode bucket : root.path("aggregations").path("by_category").path("buckets")) {
            if (bucket.path("key").asLong() == categoryId) {
                return bucket.path("doc_count").asLong(0);
            }
        }
        return hitsNode.path("hits").isEmpty() ? 0 : hitsNode.path("total").path("value").asLong(0);
    }

    @Override
    public List<String> suggest(String prefix, int limit) {
        if (prefix == null || prefix.isBlank() || limit <= 0) {
            return List.of();
        }
        ensureIndex();
        String index = properties.elasticsearch().indexName();
        String clean = prefix.strip();
        ObjectNode dsl = mapper.createObjectNode();
        dsl.put("size", Math.min(limit * 2, 20));
        ArrayNode sourceFields = dsl.putArray("_source");
        sourceFields.add("name");
        ObjectNode bool = dsl.putObject("query").putObject("bool");
        bool.putArray("filter").addObject().putObject("term").put("status", "ON_SALE");
        ArrayNode should = bool.putArray("must").addObject().putObject("bool").putArray("should");
        should.addObject().putObject("match_phrase_prefix").putObject("name.prefix")
                .put("query", clean).put("boost", 4.0);
        should.addObject().putObject("match_phrase").putObject("name.ngram")
                .put("query", clean).put("boost", 2.0);
        if (containsNonAlphanumeric(clean)) {
            should.addObject().putObject("wildcard").putObject("name.raw")
                    .put("value", "*" + escapeWildcard(clean) + "*")
                    .put("case_insensitive", true);
        }

        byte[] raw = restClient.post().uri("/{index}/_search", index)
                .contentType(MediaType.APPLICATION_JSON)
                .body(dsl.toString().getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .body(byte[].class);
        try {
            JsonNode root = mapper.readTree(raw);
            Set<String> unique = new LinkedHashSet<>();
            for (JsonNode hit : root.path("hits").path("hits")) {
                String name = hit.path("_source").path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    unique.add(name);
                    if (unique.size() >= limit) {
                        break;
                    }
                }
            }
            return List.copyOf(unique);
        } catch (Exception error) {
            throw new IllegalStateException("解析ES联想响应失败", error);
        }
    }

    private String extractSafeHighlight(JsonNode highlightNode, String rawName, String keyword) {
        if (keyword == null) {
            return null;
        }
        JsonNode fragments = highlightNode.path("name");
        if (!fragments.isArray() || fragments.isEmpty()) {
            fragments = highlightNode.path("name.ngram");
        }
        if (fragments.isArray() && !fragments.isEmpty()) {
            String marked = fragments.get(0).asText();
            String escaped = HtmlUtils.htmlEscape(marked);
            return escaped.replace(PRE_MARKER, "<mark>").replace(POST_MARKER, "</mark>");
        }
        return MysqlProductSearchGateway.highlightLiteral(rawName, keyword);
    }

    private Map<Long, String> loadCategoryNameMap() {
        long now = System.nanoTime();
        Map<Long, String> current = cachedCategoryNames;
        if (!current.isEmpty() && now - categoryCacheExpiresAtNanos < 0) {
            return current;
        }
        Map<Long, String> map = new HashMap<>();
        for (Category cat : categoryMapper.findAll()) {
            map.put(cat.getId(), cat.getName());
        }
        Map<Long, String> immutable = Map.copyOf(map);
        cachedCategoryNames = immutable;
        categoryCacheExpiresAtNanos = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        return immutable;
    }

    private static boolean containsNonAlphanumeric(String input) {
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (!Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch)) {
                return true;
            }
        }
        return false;
    }

    private static String escapeWildcard(String input) {
        return input.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }
}
