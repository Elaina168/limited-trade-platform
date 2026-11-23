package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.order.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {
    @Bean TradeAgentTools tradeAgentTools(OrderService orders) {
        return new TradeAgentTools(orders);
    }

    @Bean
    @ConditionalOnProperty(name = "app.agent.budget.enabled", havingValue = "true")
    AgentUsageLedger agentUsageLedger(JdbcTemplate jdbc, PlatformTransactionManager manager, AgentProperties properties) {
        return new AgentUsageLedger(jdbc, manager, properties);
    }

    @Bean TradeAgentService tradeAgentService(AgentProperties properties, TradeAgentTools tools, ObjectMapper mapper,
                                             Optional<RuleKnowledgeBase> knowledge, Optional<AgentUsageLedger> ledger) {
        return new TradeAgentService(properties, tools, mapper, knowledge, ledger);
    }
}
