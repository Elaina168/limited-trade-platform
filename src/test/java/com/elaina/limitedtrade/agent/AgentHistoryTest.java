package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.agent.conversation.AgentHistory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;

import static org.assertj.core.api.Assertions.*;

class AgentHistoryTest {
    @Test void fullToolTurnRoundTripsAndTrimsOnlyWholeTurns() throws Exception {
        var codec = new AgentHistory(new ObjectMapper());
        var messages = List.<Message>of(new UserMessage("查订单"),
                AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("call1", "function", "get_my_order", "{\"orderId\":9301}"))).build(),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("call1", "get_my_order", "{\"id\":9301}"))).build(),
                new AssistantMessage("订单9301"));
        String encoded = codec.encode(messages);
        assertThat(codec.decode(encoded)).isEqualTo(messages);
        var turns = new java.util.ArrayList<String>();
        for (int i = 0; i < 8; i++) turns.add(encoded);
        assertThat(codec.retain(turns)).hasSize(6);
        assertThat(codec.retain(List.of(encoded, "x".repeat(24_001)))).isEmpty();
        assertThat(codec.retain(List.of("x".repeat(13_000), "y".repeat(13_000))))
                .containsExactly("y".repeat(13_000));
    }
}
