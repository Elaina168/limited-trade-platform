package com.elaina.cloud.stock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class TraceFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);
    private final String instance;

    TraceFilter(@Value("${lab.instance}") String instance) {
        this.instance = instance;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || !traceId.matches("[a-f0-9]{32}")) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        response.setHeader("X-Trace-Id", traceId);
        response.setHeader("X-Lab-Instance", instance);
        try (var ignored = MDC.putCloseable("traceId", traceId)) {
            try {
                chain.doFilter(request, response);
            } finally {
                log.info("instance={} method={} path={} status={}", instance, request.getMethod(),
                        request.getRequestURI(), response.getStatus());
            }
        }
    }
}
