package com.elaina.cloud.order;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import feign.RequestInterceptor;

@Component
class TraceContextFilter extends OncePerRequestFilter {
    static final String HEADER = "X-Trace-Id";
    static final String KEY = "traceId";
    private static final Logger LOG = LoggerFactory.getLogger(TraceContextFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String trace = request.getHeader(HEADER);
        if (trace == null || !trace.matches("[a-f0-9]{32}")) trace = UUID.randomUUID().toString().replace("-", "");
        response.setHeader(HEADER, trace);
        response.setHeader("X-Lab-Instance", "order-service");
        try (var ignored = MDC.putCloseable(KEY, trace)) {
            try { chain.doFilter(request, response); }
            finally { LOG.info("instance=order-service method={} path={} status={}", request.getMethod(), request.getRequestURI(), response.getStatus()); }
        }
    }

    @Bean
    RequestInterceptor orderTraceInterceptor() {
        return request -> {
            String trace = MDC.get(KEY);
            if (trace != null) request.header(HEADER, trace);
        };
    }
}
