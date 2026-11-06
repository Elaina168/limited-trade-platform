package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.agent.conversation.AgentConversationController;
import com.elaina.limitedtrade.agent.conversation.AgentConversationService;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.auth.SecurityConfig;
import com.elaina.limitedtrade.user.AppUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AgentConversationController.class, properties = "app.agent.enabled=true")
@Import(SecurityConfig.class)
class AgentSseApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean AgentConversationService conversations;
    @MockitoBean AgentSseService streams;
    final String id = UUID.randomUUID().toString();
    final String path = "/api/agent/conversations/" + id + "/messages/stream";

    @Test void loginCsrfAndValidationApplyBeforeOpening() throws Exception {
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"问题\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(user("buyer")).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"问题\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf()).with(user(new PlatformUserDetails(new AppUser(7301L,"buyer","unused","USER"))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(streams);
    }

    @Test void asyncDispatchReturnsSseWithTrustedUserAndNoBuffering() throws Exception {
        when(streams.open(7301, id, "问题")).thenAnswer(call -> {
            var emitter = new SseEmitter();
            emitter.send(SseEmitter.event().name("delta").data("{\"text\":\"回答\"}"));
            emitter.send(SseEmitter.event().name("done").data("{}"));
            emitter.complete();
            return emitter;
        });
        var result = mvc.perform(post(path).with(csrf()).with(user(new PlatformUserDetails(new AppUser(7301L,"buyer","unused","USER"))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"问题\",\"userId\":7302}"))
                .andExpect(request().asyncStarted()).andExpect(header().string("X-Accel-Buffering", "no")).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
        verify(streams).open(7301, id, "问题");
    }
}
