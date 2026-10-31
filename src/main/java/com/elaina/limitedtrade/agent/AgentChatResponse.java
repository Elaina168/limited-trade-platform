package com.elaina.limitedtrade.agent;

import java.time.LocalDateTime;
import java.util.List;

public record AgentChatResponse(String answer, LocalDateTime queriedAt, List<String> toolsUsed,
                                List<TradeRule> sources, int modelCalls, int toolCalls,
                                Long totalTokens, long durationMs) { }
