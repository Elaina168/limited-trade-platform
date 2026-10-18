package com.elaina.limitedtrade.product;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration
public class ProductCacheConfiguration {

    @Bean
    public RedisTemplate<String, List<Product>> productRedisTemplate(
            RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, List<Product>> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        // 明确还原为商品列表，保持价格为 BigDecimal，不在 JSON 中启用任意类型信息。
        var listType = objectMapper.getTypeFactory().constructCollectionType(List.class, Product.class);
        template.setValueSerializer(new Jackson2JsonRedisSerializer<>(objectMapper, listType));
        return template;
    }
}
