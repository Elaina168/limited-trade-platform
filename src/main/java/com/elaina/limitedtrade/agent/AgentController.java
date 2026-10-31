package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.auth.PlatformUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "交易助手")
@RestController
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class AgentController {
    private final TradeAgentService agent;

    public AgentController(TradeAgentService agent) { this.agent = agent; }

    @PostMapping("/api/agent/chat")
    @Operation(summary = "本人订单咨询与交易规则解释", description = "仅AI启用时提供；需要Session登录和CSRF。单次请求内调用只读工具，无跨请求会话记忆。sources来自实际执行的规则工具，不是模型生成的链接。")
    @ApiResponse(responseCode = "404", description = "查询订单不存在或不属于本人")
    @ApiResponse(responseCode = "502", description = "模型服务失败或返回无效结果")
    @ApiResponse(responseCode = "503", description = "调用预算耗尽或订单数据查询失败")
    @ApiResponse(responseCode = "504", description = "模型超时或请求超过截止时间")
    public AgentChatResponse chat(@AuthenticationPrincipal PlatformUserDetails user,
                                  @Valid @RequestBody ChatRequest request) {
        return agent.chat(user.getId(), request.message());
    }

    public record ChatRequest(@NotBlank @Size(max = 2000) String message) { }
}
