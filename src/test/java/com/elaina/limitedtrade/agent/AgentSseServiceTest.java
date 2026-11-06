package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.agent.conversation.AgentConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSseServiceTest {
    @Test void eightActiveStreamsRejectNinthWithoutClaimingAnotherConversation() throws Exception {
        var conversations = mock(AgentConversationService.class);
        var prepared = new AgentConversationService.PreparedTurn(1, "id", "问题", 0, "token", List.of());
        when(conversations.prepare(anyLong(), anyString(), anyString())).thenReturn(prepared);
        var entered = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        when(conversations.stream(eq(prepared), any())).thenAnswer(call -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new AgentChatResponse("回答", java.time.LocalDateTime.now(), List.of(), List.of(), 1, 0, 1L, 1, List.of());
        });
        var settings = Binder.get(new MockEnvironment().withProperty("app.agent.api-key", "test"))
                .bind("app.agent", AgentProperties.class).get();
        try (var service = new AgentSseService(conversations, settings, new ObjectMapper().findAndRegisterModules())) {
            for (int i = 0; i < 8; i++) service.open(1, "id" + i, "问题");
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.open(1, "ninth", "问题")).isInstanceOfSatisfying(ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
            verify(conversations, times(8)).prepare(anyLong(), anyString(), anyString());
            release.countDown();
        }
    }

    @Test void validationFailureReleasesCapacityBeforeOpeningStream() {
        var conversations = mock(AgentConversationService.class);
        when(conversations.prepare(anyLong(), anyString(), anyString()))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        var settings = Binder.get(new MockEnvironment().withProperty("app.agent.api-key", "test"))
                .bind("app.agent", AgentProperties.class).get();
        try (var service = new AgentSseService(conversations, settings, new ObjectMapper())) {
            for (int i = 0; i < 12; i++) assertThatThrownBy(() -> service.open(1, "missing", "问题"))
                    .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        }
        verify(conversations, times(12)).prepare(anyLong(), anyString(), anyString());
    }
}
