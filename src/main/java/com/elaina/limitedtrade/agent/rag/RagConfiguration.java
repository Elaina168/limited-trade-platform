package com.elaina.limitedtrade.agent.rag;

import com.elaina.limitedtrade.agent.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.agent", name = {"enabled", "rag.enabled"}, havingValue = "true")
public class RagConfiguration {
    @Bean(destroyMethod = "close") BgeEmbeddingModel bgeEmbeddingModel(AgentProperties properties) throws Exception {
        return new BgeEmbeddingModel(properties.rag().modelDirectory());
    }

    @Bean RuleKnowledgeBase ruleKnowledgeBase(BgeEmbeddingModel model, AgentProperties properties, ObjectMapper mapper) throws Exception {
        return new RuleKnowledgeBase(model, properties.rag(), mapper);
    }
}
