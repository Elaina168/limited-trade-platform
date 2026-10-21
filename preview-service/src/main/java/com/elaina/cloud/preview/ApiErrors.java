package com.elaina.cloud.preview;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> response(ResponseStatusException error){
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error",error.getReason(),"traceId",MDC.get("traceId")));
    }
}
