package com.elaina.limitedtrade.agent;

import java.time.LocalDateTime;
import java.util.List;
import com.elaina.limitedtrade.agent.rag.RuleChunk;

public record AgentChatResponse(String answer, LocalDateTime queriedAt, List<String> toolsUsed,
                                List<TradeRule> sources, int modelCalls, int toolCalls,
                                Long totalTokens, long durationMs, List<RuleChunk> knowledgeSources) { }
