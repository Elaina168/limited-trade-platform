package com.elaina.cloud.stock;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class StockController {
    static final String PRODUCT_DETAIL = "product-detail-by-id";
    private final StockTasks tasks;
    private final StockCheckMessaging messages;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Environment config;
    private final String cachePrefix;
    private final String instance;

    StockController(StockTasks tasks, StockCheckMessaging messages, JdbcTemplate jdbc, StringRedisTemplate redis,
                    ObjectMapper json, Environment config, @Value("${lab.stock.cache-prefix}") String cachePrefix,
                    @Value("${lab.instance}") String instance) {
        this.tasks = tasks;
        this.messages = messages;
        this.jdbc = jdbc;
        this.redis = redis;
        this.json = json;
        this.config = config;
        this.cachePrefix = cachePrefix;
        this.instance = instance;
    }

    @PostConstruct
    void installHotspotRule() {
        double qps = config.getRequiredProperty("lab.product.hot-qps", Double.class);
        ParamFlowRuleManager.loadRules(List.of(new ParamFlowRule(PRODUCT_DETAIL)
                .setGrade(RuleConstant.FLOW_GRADE_QPS).setParamIdx(0).setCount(qps)));
    }

    @EventListener
    void refreshHotspotRule(EnvironmentChangeEvent event) {
        if (event.getKeys().contains("lab.product.hot-qps")) installHotspotRule();
    }

    @GetMapping("/internal/products/{id}")
    Product detail(@PathVariable long id) throws JsonProcessingException, InterruptedException {
        Entry entry;
        try {
            entry = SphU.entry(PRODUCT_DETAIL, EntryType.IN, 1, id);
        } catch (BlockException limited) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "商品详情请求过于频繁", limited);
        }
        try {
            Thread.sleep(config.getRequiredProperty("lab.product.delay-ms", Long.class));
            // 缓存商品基础信息；库存每次读 MySQL，双实例返回同一个已提交库存版本。
            String key = cachePrefix + ":product-metadata:v1:" + id;
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                var rows = jdbc.queryForList("SELECT stock FROM stock_product WHERE id=?", Integer.class, id);
                if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
                var metadata = json.readValue(cached, Metadata.class);
                return new Product(id, metadata.name(), metadata.price(), rows.getFirst(), instance);
            }
            List<Product> products = jdbc.query("SELECT name,price,stock FROM stock_product WHERE id=?",
                    (rs, row) -> new Product(id, rs.getString(1), rs.getBigDecimal(2), rs.getInt(3), instance), id);
            if (products.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
            Product result = products.getFirst();
            redis.opsForValue().set(key, json.writeValueAsString(new Metadata(result.name(), result.price())),
                    Duration.ofMinutes(5));
            return result;
        } finally {
            entry.exit(1, id);
        }
    }

    @PostMapping("/internal/stock/{orderId}/lock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void lock(@PathVariable long orderId, @RequestBody LockRequest request) {
        if (orderId <= 0 || request.productId() <= 0 || request.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单、商品编号和购买数量必须为正数");
        }
        if (tasks.lock(orderId, request.productId(), request.quantity(), MDC.get("traceId"))) messages.send(orderId);
    }

    @PostMapping("/internal/stock/{orderId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable long orderId) {
        if (orderId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单编号必须为正数");
        tasks.release(orderId);
    }

    record Metadata(String name, BigDecimal price) {}
    record Product(long id, String name, BigDecimal price, int stock, String instance) {}
    record LockRequest(long productId, int quantity) {}
}
