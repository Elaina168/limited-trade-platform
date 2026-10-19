package com.elaina.limitedtrade.order;

import java.io.IOException;
import java.net.ServerSocket;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.ratelimit.OrderRateLimitProperties;
import com.elaina.limitedtrade.ratelimit.RedisOrderRateLimiter;
import com.elaina.limitedtrade.user.AppUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties="app.order-rate-limit.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/sql/order-test-data.sql")
class OrderRateLimitUnavailableTest {
    private static final int CLOSED_PORT;
    static {try(var socket=new ServerSocket(0)){CLOSED_PORT=socket.getLocalPort();}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void unavailable(DynamicPropertyRegistry r){
        r.add("spring.data.redis.port",()->CLOSED_PORT);
        r.add("app.order-rate-limit.key-prefix",()->"limited-trade:test:unavailable");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean StringRedisTemplate redis;
    @AfterEach void restoreExternalBoundary(){reset(redis);}

    @Test void realConnectionRefusalRejectsDirectOrderBeforeAnyBusinessWrite()throws Exception {
        directUnavailable();unchanged();
    }

    @Test void realConnectionRefusalRejectsCheckoutBeforeCartAndAddressLookup()throws Exception {
        mvc.perform(post("/api/orders/checkout").with(user(new PlatformUserDetails(new AppUser(7101L,"rate_limit_buyer","unused","USER")))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[9101],\"addressId\":1,\"requestId\":\"redis-down-cart\"}"))
                .andExpect(status().isServiceUnavailable());unchanged();
    }

    @Test @SuppressWarnings("unchecked")
    void queryTimeoutIsAlso503WithoutWritingAnOrder()throws Exception {
        doThrow(new QueryTimeoutException("controlled external Redis timeout"))
                .when(redis).execute(any(RedisScript.class),anyList(),anyString(),anyString());
        directUnavailable();unchanged();
    }

    @Test void disablingLimitDoesNotConnectEvenWhenRedisIsUnavailable(){
        var disabled=new RedisOrderRateLimiter(redis,new OrderRateLimitProperties(false,5,10,"limited-trade:test:disabled"));
        assertDoesNotThrow(()->disabled.check(7101));unchanged();
    }

    private void directUnavailable()throws Exception {
        mvc.perform(post("/api/orders").with(user(new PlatformUserDetails(new AppUser(7101L,"rate_limit_buyer","unused","USER")))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":9101,\"quantity\":1,\"requestId\":\"redis-down\"}"))
                .andExpect(status().isServiceUnavailable());
    }
    private void unchanged(){
        for(String table:java.util.List.of("trade_order","order_item","order_address","order_timeout_outbox","order_shipment"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        assertEquals(5,jdbc.queryForObject("SELECT stock FROM product WHERE id=9101",Integer.class));
    }
}
