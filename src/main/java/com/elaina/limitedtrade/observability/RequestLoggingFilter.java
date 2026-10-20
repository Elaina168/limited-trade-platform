package com.elaina.limitedtrade.observability;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 每次HTTP请求生成独立编号，不复用客户端传值或下单幂等requestId。
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String previous = MDC.get("traceId");
        MDC.put("traceId", traceId);
        response.setHeader("X-Trace-Id", traceId);
        long started = System.nanoTime();
        Throwable failure = null;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                int status = failure == null ? response.getStatus() : 500;
                Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                if (route == null) {
                    // Security提前结束的登录/退出没有MVC路由，其余不记录原始URL。
                    String path = request.getRequestURI().substring(request.getContextPath().length());
                    route = "/api/auth/login".equals(path) || "/api/auth/logout".equals(path) ? path : "UNMAPPED";
                }
                var event = status >= 500 ? log.atError() : status >= 400 ? log.atWarn() : log.atInfo();
                event.addKeyValue("event", "http_request").addKeyValue("method", request.getMethod())
                        .addKeyValue("route", route).addKeyValue("status", status)
                        .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                if (failure != null) {
                    var root = NestedExceptionUtils.getMostSpecificCause(failure);
                    // 保留异常类型和代码位置，不输出可能含用户输入、SQL值的异常消息。
                    event.addKeyValue("exceptionType", root.getClass().getName())
                            .addKeyValue("exceptionStack", Arrays.toString(root.getStackTrace()));
                }
                event.log("接口请求结束");
            } finally {
                if (previous == null) MDC.remove("traceId"); else MDC.put("traceId", previous);
            }
        }
    }
}
