package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.order.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AgentConfiguration.class)
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

    @Test void invalidBudgetFailsStartup() {
        runner.withPropertyValues("app.agent.enabled=true", "app.agent.api-key=test-key",
                        "app.agent.max-tool-calls=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
