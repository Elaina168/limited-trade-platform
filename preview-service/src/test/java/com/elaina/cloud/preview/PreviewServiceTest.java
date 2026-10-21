package com.elaina.cloud.preview;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PreviewServiceTest {
    @Test void calculatesWithDownstreamPriceAndReturnsInstance() {
        var builder = RestClient.builder().baseUrl("http://product-service");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://product-service/api/products/1")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"id\":1,\"name\":\"实验商品\",\"price\":19.90,\"instance\":\"product-a\"}", MediaType.APPLICATION_JSON));
        var service = new PreviewService(builder.build(), CircuitBreaker.ofDefaults("test"), "cloud-v1");
        var result = service.preview(1,3,"trace-unit");
        assertEquals(new BigDecimal("59.70"),result.totalAmount());
        assertEquals("product-a",result.productInstance());
        assertEquals("cloud-v1",result.campaign());
        server.verify();
    }
    @Test void downstreamFailureDoesNotFabricateSuccessfulPrice() {
        var builder=RestClient.builder().baseUrl("http://product-service");
        var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://product-service/api/products/1")).andRespond(withServerError());
        var service=new PreviewService(builder.build(),CircuitBreaker.ofDefaults("failure"),"cloud-v1");
        var error=assertThrows(ResponseStatusException.class,()->service.preview(1,1,"trace-unit"));
        assertEquals(502,error.getStatusCode().value());
        server.verify();
    }
}
