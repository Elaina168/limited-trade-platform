package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.auth.SecurityConfig;
import com.elaina.limitedtrade.user.AppUser;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AgentController.class, properties = "app.agent.enabled=true")
@Import(SecurityConfig.class)
class AgentApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean TradeAgentService service;

    @Test void anonymousWithCsrfStillNeedsLogin() throws Exception {
        mvc.perform(post("/api/agent/chat").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"查询订单\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void loggedInWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/api/agent/chat").with(user("buyer")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"查询订单\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void authenticatedRequestUsesPrincipalInsteadOfClientUserId() throws Exception {
        var answer = new AgentChatResponse("没有订单", LocalDateTime.now(), List.of("list_my_orders"),
                List.of(), 2, 1, 30L, 20, List.of());
        when(service.chat(7301L, "查询订单")).thenReturn(answer);
        mvc.perform(post("/api/agent/chat").with(csrf()).with(user(new PlatformUserDetails(
                                new AppUser(7301L, "buyer", "unused", "USER"))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"查询订单\",\"userId\":7302}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("没有订单"))
                .andExpect(jsonPath("$.toolCalls").value(1));
        verify(service).chat(7301L, "查询订单");
    }

    @Test void blankAndOversizedMessagesAreRejected() throws Exception {
        var principal = user(new PlatformUserDetails(new AppUser(7301L, "buyer", "unused", "USER")));
        mvc.perform(post("/api/agent/chat").with(csrf()).with(principal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/agent/chat").with(csrf()).with(principal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + "a".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
