package com.elaina.limitedtrade.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class OrderRateLimitConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(OrderRateLimitConfiguration.class)
            .withPropertyValues("app.order-rate-limit.enabled=true","app.order-rate-limit.max-requests=5",
                    "app.order-rate-limit.window-seconds=10","app.order-rate-limit.key-prefix=limited-trade:test:config");

    @Test void validConfigurationBindsAtStartup(){
        runner.run(context->{assertThat(context).hasNotFailed();assertThat(context).hasSingleBean(OrderRateLimitProperties.class);});
    }

    @ParameterizedTest @ValueSource(strings={
            "app.order-rate-limit.max-requests=0","app.order-rate-limit.max-requests=-1",
            "app.order-rate-limit.window-seconds=0","app.order-rate-limit.window-seconds=-1",
            "app.order-rate-limit.key-prefix=","app.order-rate-limit.key-prefix=\u3000",
            "app.order-rate-limit.key-prefix=invalid prefix","app.order-rate-limit.max-requests=2147483648",
            "app.order-rate-limit.max-request=3"})
    void invalidOrMisspelledConfigurationStopsStartup(String invalid){
        runner.withPropertyValues(invalid).run(context->assertThat(context).hasFailed());
    }
}
