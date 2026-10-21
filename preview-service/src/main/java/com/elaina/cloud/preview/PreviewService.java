package com.elaina.cloud.preview;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PreviewService {
    private final RestClient client;
    private final CircuitBreaker circuit;
    private final String campaign;
    public PreviewService(RestClient client,CircuitBreaker circuit,@Value("${lab.campaign}") String campaign){
        this.client=client;this.circuit=circuit;this.campaign=campaign;
    }
    public Preview preview(long productId,int quantity,String trace){
        Product product;
        try{
            product=circuit.executeSupplier(()->client.get().uri("/api/products/{id}",productId)
                .header("X-Trace-Id",trace).retrieve().body(Product.class));
        }catch(CallNotPermittedException error){
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"CIRCUIT_OPEN",error);
        }catch(ResourceAccessException error){
            var root=org.springframework.core.NestedExceptionUtils.getMostSpecificCause(error);
            throw new ResponseStatusException(root instanceof HttpTimeoutException ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE,
                "DOWNSTREAM_UNAVAILABLE",error);
        }catch(HttpServerErrorException error){
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"DOWNSTREAM_FAILED",error);
        }catch(HttpClientErrorException error){
            throw new ResponseStatusException(error.getStatusCode(),"DOWNSTREAM_REJECTED",error);
        }
        return new Preview(product.id(),product.name(),quantity,product.price(),product.price().multiply(BigDecimal.valueOf(quantity)),
            product.instance(),campaign,trace);
    }
    public record Product(long id,String name,BigDecimal price,String instance){}
    public record Preview(long productId,String name,int quantity,BigDecimal unitPrice,BigDecimal totalAmount,
        String productInstance,String campaign,String traceId){}
}
