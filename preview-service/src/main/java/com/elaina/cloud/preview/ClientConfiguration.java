package com.elaina.cloud.preview;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

@Configuration(proxyBeanMethods=false)
public class ClientConfiguration {
    @Bean @LoadBalanced RestClient.Builder productClientBuilder(){
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        var factory=new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(600));
        return RestClient.builder().requestFactory(factory);
    }
    @Bean RestClient productClient(@LoadBalanced RestClient.Builder builder){return builder.baseUrl("http://product-service").build();}
    @Bean CircuitBreaker productCircuit(){
        var config=CircuitBreakerConfig.custom().slidingWindowSize(4).minimumNumberOfCalls(4)
            .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(3))
            .permittedNumberOfCallsInHalfOpenState(2)
            .recordException(error->error instanceof ResourceAccessException ||
                error instanceof RestClientResponseException response && response.getStatusCode().is5xxServerError())
            .ignoreException(error->error instanceof RestClientResponseException response && response.getStatusCode().is4xxClientError())
            .build();
        return CircuitBreaker.of("product",config);
    }
}
