package com.elaina.limitedtrade.system;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "系统")
@RestController
public class AdminReleaseController {

    private final ReleaseInfoService releaseInfoService;

    public AdminReleaseController(ReleaseInfoService releaseInfoService) {
        this.releaseInfoService = releaseInfoService;
    }

    @GetMapping("/api/admin/operations/release")
    @Operation(summary = "查询发布版本与迁移兼容性快照",
            description = "只读；暴露应用构建产物、Git提交SHA、发布标签、数据库已应用迁移集合及脱敏功能开关，不含任何密码或API密钥。"
                    + "数据库不可达或缺少必需迁移时返回503。")
    public ResponseEntity<ReleaseInfoService.ReleaseSnapshot> inspectRelease() {
        ReleaseInfoService.ReleaseSnapshot snapshot = releaseInfoService.snapshot();
        HttpStatus status = snapshot.database().compatible() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(snapshot);
    }
}
