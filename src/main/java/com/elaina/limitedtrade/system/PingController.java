package com.elaina.limitedtrade.system;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "系统")
@RestController
public class PingController {

    @GetMapping("/api/ping")
    @Operation(summary = "检查接口连通性", description = "返回message=pong，不代表所有外部依赖健康。")
    public Map<String, String> ping() {
        return Map.of("message", "pong");
    }
}
