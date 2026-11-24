package com.elaina.limitedtrade.agent.rag;

import com.elaina.limitedtrade.agent.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.agent", name = {"enabled", "rag.enabled"}, havingValue = "true")
public class RagConfiguration {
    @Lazy
    @Bean(destroyMethod = "close") BgeEmbeddingModel bgeEmbeddingModel(AgentProperties properties) throws Exception {
        return new BgeEmbeddingModel(properties.rag().modelDirectory());
    }

    @Bean RuleKnowledgeBase ruleKnowledgeBase(ObjectProvider<BgeEmbeddingModel> models, AgentProperties properties,
                                                  ObjectMapper mapper) throws Exception {
        var settings = properties.rag();
        var model = settings.retrievalMode() == RagProperties.RetrievalMode.KEYWORD ? null : models.getObject();
        return new RuleKnowledgeBase(model, settings, mapper);
    }
}
