package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.order.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {
    @Bean TradeAgentTools tradeAgentTools(OrderService orders) {
        return new TradeAgentTools(orders);
    }

    @Bean TradeAgentService tradeAgentService(AgentProperties properties, TradeAgentTools tools, ObjectMapper mapper) {
        return new TradeAgentService(properties, tools, mapper);
    }
}
