package com.elaina.limitedtrade.agent.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.*;

public class AgentHistory {
    private final ObjectMapper mapper;

    public AgentHistory(ObjectMapper mapper) { this.mapper = mapper; }

    public String encode(List<Message> messages) {
        var stored = messages.stream().map(message -> switch (message.getMessageType()) {
            case USER -> new StoredMessage("user", message.getText(), List.of(), List.of());
            case ASSISTANT -> new StoredMessage("assistant", message.getText(),
                    ((AssistantMessage) message).getToolCalls(), List.of());
            case TOOL -> new StoredMessage("tool", "", List.of(), ((ToolResponseMessage) message).getResponses());
            default -> throw new IllegalArgumentException("会话历史不保存系统消息");
        }).toList();
        try { return mapper.writeValueAsString(stored); }
        catch (JsonProcessingException error) { throw new IllegalStateException("会话历史序列化失败", error); }
    }

    public List<Message> decode(String json) {
        try {
            List<StoredMessage> stored = mapper.readValue(json, new TypeReference<>() { });
            return stored.stream().<Message>map(message -> switch (message.role()) {
                case "user" -> new UserMessage(message.text());
                case "assistant" -> AssistantMessage.builder().content(message.text()).toolCalls(message.calls()).build();
                case "tool" -> ToolResponseMessage.builder().responses(message.responses()).build();
                default -> throw new IllegalStateException("会话历史包含不支持的角色");
            }).toList();
        } catch (JsonProcessingException error) { throw new IllegalStateException("会话历史反序列化失败", error); }
    }

    public List<String> retain(List<String> turns) {
        // 从最新轮次向前取连续历史；不截断一轮内部的工具调用与结果。
        int first = turns.size();
        int chars = 0;
        while (first > 0 && turns.size() - first < 6) {
            int length = turns.get(first - 1).length();
            if (chars + length > 24_000) break;
            chars += length;
            first--;
        }
        return List.copyOf(turns.subList(first, turns.size()));
    }

    public List<Message> messages(List<String> turns) {
        var result = new ArrayList<Message>();
        for (var turn : retain(turns)) result.addAll(decode(turn));
        return List.copyOf(result);
    }

    public record StoredMessage(String role, String text, List<AssistantMessage.ToolCall> calls,
                                List<ToolResponseMessage.ToolResponse> responses) { }
}
