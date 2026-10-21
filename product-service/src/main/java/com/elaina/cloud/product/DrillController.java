package com.elaina.cloud.product;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

// 故障注入仅在显式drill配置下启用，不经过网关路由。
@Profile("drill")
@RestController
@RequestMapping("/lab/mode")
public class DrillController {
    private final ProductMode mode;
    public DrillController(ProductMode mode){this.mode=mode;}
    @PutMapping public Mode set(@RequestBody Mode value){
        if(!java.util.Set.of("normal","slow","fail").contains(value.mode()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"未知实验模式");
        mode.current.set(value.mode()); return value;
    }
    public record Mode(String mode) {}
}
