package com.elaina.limitedtrade.agent.conversation;

import java.time.LocalDateTime;

public record AgentConversation(String id, long userId, long revision, String generationToken,
                                LocalDateTime busyUntil, LocalDateTime expiresAt) { }
