package com.elaina.limitedtrade.agent;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.bind.DefaultValue;

public record AgentBudgetProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("100") @DecimalMin("0.000001") @Digits(integer = 6, fraction = 6) BigDecimal monthlyYuan,
        @DefaultValue("50") @Min(1) int dailyQuestionsPerUser,
        @DefaultValue("1") @Min(1) int concurrentPerUser,
        @DefaultValue("4") @Min(1) int concurrentGlobal,
        @DefaultValue("32768") @Min(2048) @Max(131072) int contextTokens) {
}
