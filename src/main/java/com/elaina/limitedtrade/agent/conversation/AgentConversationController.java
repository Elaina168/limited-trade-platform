package com.elaina.limitedtrade.agent.conversation;

import com.elaina.limitedtrade.agent.AgentChatResponse;
import com.elaina.limitedtrade.agent.AgentSseService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.elaina.limitedtrade.agent.AgentController.ChatRequest;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "交易助手多轮会话")
@RequestMapping("/api/agent/conversations")
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class AgentConversationController {
    private final AgentConversationService service;
    private final AgentSseService streams;

    public AgentConversationController(AgentConversationService service, AgentSseService streams) {
        this.service = service;
        this.streams = streams;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建本人Agent会话", description = "需Session和CSRF；每用户最多10个未过期会话，无活动24小时过期。")
    public AgentConversationService.CreatedConversation create(@AuthenticationPrincipal PlatformUserDetails user) {
        return service.create(user.getId());
    }

    @PostMapping("/{conversationId}/messages")
    @Operation(summary = "向本人会话发送问题", description = "不存在、过期或非本人返回404；正在生成或会话版本变化返回409。来源仅包含本次实际调用结果。")
    public AgentChatResponse chat(@AuthenticationPrincipal PlatformUserDetails user,
                                   @PathVariable UUID conversationId, @Valid @RequestBody ChatRequest request) {
        return service.chat(user.getId(), conversationId.toString(), request.message());
    }

    @DeleteMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "清空本人会话历史", description = "需CSRF；清空使旧生成资格失效，进行中的旧回答不能重新写入历史。")
    public void clear(@AuthenticationPrincipal PlatformUserDetails user, @PathVariable UUID conversationId) {
        service.clear(user.getId(), conversationId.toString());
    }

    @PostMapping(value = "/{conversationId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "本人会话流式问答", description = "Session和CSRF；start/delta/reset/done/error事件，只有done代表完整回答已保存。")
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal PlatformUserDetails user,
                                              @PathVariable UUID conversationId, @Valid @RequestBody ChatRequest request) {
        var emitter = streams.open(user.getId(), conversationId.toString(), request.message());
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").header("Cache-Control", "no-cache")
                .contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }
}
