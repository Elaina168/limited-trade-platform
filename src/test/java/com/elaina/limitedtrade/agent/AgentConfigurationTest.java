package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.order.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AgentConfiguration.class, com.elaina.limitedtrade.agent.rag.RagConfiguration.class)
            .withBean(OrderService.class, () -> mock(OrderService.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test void disabledAgentDoesNotNeedCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TradeAgentService.class);
        });
    }

    @Test void enabledAgentWithoutKeyFailsStartup() {
        runner.withPropertyValues("app.agent.enabled=true").run(context -> assertThat(context).hasFailed());
    }

    @Test void validSettingsCreateAgentWithoutNetworkAccess() {
        runner.withPropertyValues("app.agent.enabled=true", "app.agent.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TradeAgentService.class);
                    assertThat(context.getBean(AgentProperties.class).model()).isEqualTo("deepseek-flash");
                });
    }

    @Test void monthlyBudgetSettingsBindAtStartup() {
        runner.withPropertyValues("app.agent.enabled=true", "app.agent.api-key=test-key",
                        "app.agent.budget.monthly-yuan=100")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test void invalidBudgetFailsStartup() {
        runner.withPropertyValues("app.agent.enabled=true", "app.agent.api-key=test-key",
                        "app.agent.max-tool-calls=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void disabledRagDoesNotLoadMissingModel() {
        runner.withPropertyValues("app.agent.enabled=true", "app.agent.api-key=test-key",
                        "app.agent.rag.model-directory=tools/missing-model")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(com.elaina.limitedtrade.agent.rag.BgeEmbeddingModel.class);
                    assertThat(context.getBean(AgentProperties.class).rag().similarityThreshold()).isEqualTo(0.55);
                });
    }

    @Test void retrievalExperimentsCanBeEnabledIndependently() {
        runner.withPropertyValues("app.agent.enabled=true", "app.agent.api-key=test-key",
                        "app.agent.rag.rerank-enabled=true", "app.agent.rag.query-rewrite-enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test void keywordOnlyStartsWithoutLocalEmbeddingFiles() {
        runner.withPropertyValues("app.agent.enabled=true", "app.agent.api-key=test-key",
                        "app.agent.rag.enabled=true", "app.agent.rag.retrieval-mode=keyword",
                        "app.agent.rag.model-directory=tools/missing-model")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var result = context.getBean(com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.class)
                            .search("付款期限 expiresAt 到了还能支付吗？");
                    assertThat(result.chunks()).isNotEmpty();
                });
    }

    @Test void enabledRagRequiresLocalModelAndValidSettings() {
        runner.withPropertyValues("app.agent.enabled=true", "app.agent.api-key=test-key",
                        "app.agent.rag.enabled=true", "app.agent.rag.model-directory=tools/missing-model")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.agent.enabled=true", "app.agent.api-key=test-key", "app.agent.rag.top-k=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
