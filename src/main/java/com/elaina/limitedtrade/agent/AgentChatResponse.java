package com.elaina.limitedtrade.agent;

import java.time.LocalDateTime;
import java.util.List;
import com.elaina.limitedtrade.agent.rag.RuleChunk;

public record AgentChatResponse(String answer, LocalDateTime queriedAt, List<String> toolsUsed,
                                List<TradeRule> sources, int modelCalls, int toolCalls,
                                Long totalTokens, long durationMs, List<RuleChunk> knowledgeSources,
                                List<AgentOrderSummary> orders) {
    public AgentChatResponse(String answer, LocalDateTime queriedAt, List<String> toolsUsed,
                             List<TradeRule> sources, int modelCalls, int toolCalls,
                             Long totalTokens, long durationMs, List<RuleChunk> knowledgeSources) {
        this(answer, queriedAt, toolsUsed, sources, modelCalls, toolCalls, totalTokens, durationMs,
                knowledgeSources, List.of());
    }
}
