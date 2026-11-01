package com.elaina.limitedtrade.agent;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

// 只包含当前成功轮次，供会话层保存；不含系统消息或先前轮次。
public record AgentTurn(AgentChatResponse response, List<Message> messages) { }
