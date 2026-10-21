package com.elaina.cloud.product;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceFilter extends OncePerRequestFilter {
    private final String instance;
    public TraceFilter(@Value("${lab.instance}") String instance){this.instance=instance;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String trace=request.getHeader("X-Trace-Id");
        if(trace==null || !trace.matches("[a-f0-9]{32}")) trace=UUID.randomUUID().toString().replace("-", "");
        MDC.put("traceId",trace);
        response.setHeader("X-Trace-Id",trace);
        response.setHeader("X-Lab-Instance",instance);
        try{chain.doFilter(request,response);}
        finally{
            LoggerFactory.getLogger(TraceFilter.class).info("instance={} method={} path={} status={}",instance,request.getMethod(),request.getRequestURI(),response.getStatus());
            MDC.remove("traceId");
        }
    }
}
