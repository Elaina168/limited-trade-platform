package com.elaina.limitedtrade.system;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.release.gate-traffic", havingValue = "true")
class ReleaseTrafficFilter extends OncePerRequestFilter {
    private final ReleaseInfoService releases;
    private volatile boolean compatible;

    ReleaseTrafficFilter(ReleaseInfoService releases) {
        this.releases = releases;
    }

    @EventListener(ApplicationReadyEvent.class)
    void checkSchema() {
        // 实例启动时固定准入结果，完成迁移后通过重启重新校验。
        compatible = releases.databaseState().compatible();
    }

    boolean acceptsTraffic() {
        return compatible;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (!compatible && path.startsWith("/api/") && !path.equals("/api/ping")) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        chain.doFilter(request, response);
    }
}
