package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.agent.conversation.AgentConversationController;
import com.elaina.limitedtrade.agent.conversation.AgentConversationService;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.auth.SecurityConfig;
import com.elaina.limitedtrade.user.AppUser;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AgentConversationController.class, properties = "app.agent.enabled=true")
@Import(SecurityConfig.class)
class AgentConversationApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean AgentConversationService service;
    @MockitoBean AgentSseService streams;
    private final String id = UUID.randomUUID().toString();

    private org.springframework.test.web.servlet.request.RequestPostProcessor principal() {
        return user(new PlatformUserDetails(new AppUser(7301L, "buyer", "unused", "USER")));
    }

    @Test void allEndpointsRequireLoginAndCsrf() throws Exception {
        var path = "/api/agent/conversations/" + id + "/messages";
        mvc.perform(post("/api/agent/conversations").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"查订单\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(path).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/conversations").with(principal())).andExpect(status().isForbidden());
        mvc.perform(post(path).with(principal())).andExpect(status().isForbidden());
        mvc.perform(delete(path).with(principal())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void usesAuthenticatedOwnerAndExposesCreateSendClear() throws Exception {
        when(service.create(7301)).thenReturn(new AgentConversationService.CreatedConversation(id, LocalDateTime.now().plusHours(24)));
        when(service.chat(7301, id, "第一笔呢")).thenReturn(new AgentChatResponse("9301", LocalDateTime.now(),
                List.of(), List.of(), 1, 0, 1L, 1, List.of()));
        mvc.perform(post("/api/agent/conversations").with(principal()).with(csrf()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.conversationId").value(id));
        mvc.perform(post("/api/agent/conversations/" + id + "/messages").with(principal()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"第一笔呢\",\"userId\":7302}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("9301"));
        mvc.perform(delete("/api/agent/conversations/" + id + "/messages").with(principal()).with(csrf()))
                .andExpect(status().isNoContent());
        verify(service).create(7301);
        verify(service).chat(7301, id, "第一笔呢");
        verify(service).clear(7301, id);
    }

    @Test void invalidInputIsRejectedAndDomainStatusesRemainVisible() throws Exception {
        var path = "/api/agent/conversations/" + id + "/messages";
        for (String text : List.of(" ", "a".repeat(2001))) {
            mvc.perform(post(path).with(principal()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"" + text + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(delete("/api/agent/conversations/invalid/messages").with(principal()).with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        when(service.chat(7301, id, "不存在")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(service.chat(7301, id, "并发")).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
        mvc.perform(post(path).with(principal()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"不存在\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(path).with(principal()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"并发\"}"))
                .andExpect(status().isConflict());
    }
}
