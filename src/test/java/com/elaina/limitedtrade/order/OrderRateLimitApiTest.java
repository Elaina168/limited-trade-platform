package com.elaina.limitedtrade.order;

import java.util.*;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.user.AppUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.order-rate-limit.enabled=true","app.order-rate-limit.max-requests=3","app.order-rate-limit.window-seconds=30"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrderRateLimitApiTest {
    private static final String PREFIX="limited-trade:test:order-rate-limit:api:"+UUID.randomUUID();
    @DynamicPropertySource static void prefix(DynamicPropertyRegistry r){r.add("app.order-rate-limit.key-prefix",()->PREFIX);}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    private final List<MockHttpSession> sessions=new ArrayList<>();

    @BeforeEach void prepare(){
        String hash=passwords.encode("RateLimitSession2026!");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (8201,'rate_limit_a',?,'USER'),(8202,'rate_limit_b',?,'USER'),(8203,'rate_limit_admin',?,'ADMIN')",hash,hash,hash);
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (16001,'限流键盘',20,20,'ON_SALE'),(16002,'限流鼠标',7.50,10,'ON_SALE')");
        jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (8201,16002,2),(8202,16002,1)");
        jdbc.update("INSERT INTO user_address(id,user_id,recipient_name,phone,province,city,district,detail_address) VALUES (17001,8201,'张三','13800138000','浙江','杭州','西湖','学院路1号'),(17002,8202,'李四','13900139000','江苏','南京','鼓楼','测试路2号')");
        redis.delete(List.of(key(8201),key(8202),key(8203)));
    }
    @AfterEach void cleanup(){
        for(var s:sessions)if(!s.isInvalid())s.invalidate();
        redis.delete(List.of(key(8201),key(8202),key(8203)));
    }

    @Test void directAndCheckoutShareBudgetAndRejectionHasNoBusinessSideEffects()throws Exception {
        direct(8201,"first",201);
        mvc.perform(post("/api/orders/checkout").with(buyer(8201)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productIds\":[16002],\"addressId\":17001,\"requestId\":\"cart-first\"}"))
                .andExpect(status().isCreated());
        direct(8201,"first",200);
        var before=business();
        var response=mvc.perform(post("/api/orders").with(buyer(8201)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body("blocked"))).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After")).andReturn().getResponse();
        long wait=Long.parseLong(response.getHeader("Retry-After"));assertTrue(wait>=1&&wait<=31);
        mvc.perform(post("/api/orders/checkout").with(buyer(8201)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productIds\":[16002],\"addressId\":17001,\"requestId\":\"cart-blocked\"}"))
                .andExpect(status().isTooManyRequests());
        assertEquals(before,business());assertEquals("3",redis.opsForValue().get(key(8201)));
    }

    @Test void separateUsersHaveIndependentBudgets()throws Exception {
        for(int i=0;i<3;i++)direct(8201,"user-a-"+i,201);
        direct(8201,"blocked",429);direct(8202,"user-b-first",201);
        assertEquals("3",redis.opsForValue().get(key(8201)));assertEquals("1",redis.opsForValue().get(key(8202)));
    }

    @Test void adminBuyingAlsoUsesTheSameUserQuota()throws Exception {
        for(int i=0;i<3;i++)direct(8203,"admin-"+i,201);
        direct(8203,"admin-blocked",429);
    }

    @Test void failedBusinessRequestsConsumeAttemptsWithoutCreatingOrders()throws Exception {
        for(int i=0;i<3;i++)mvc.perform(post("/api/orders").with(buyer(8201)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":999999,\"quantity\":1,\"requestId\":\"missing-"+i+"\"}"))
                .andExpect(status().isNotFound());
        direct(8201,"blocked-after-failure",429);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trade_order",Integer.class));
        assertEquals(20,jdbc.queryForObject("SELECT stock FROM product WHERE id=16001",Integer.class));
    }

    @Test void conflictingAndIdempotentRequestsConsumeQuotaWithoutDuplicatePurchase()throws Exception {
        direct(8201,"same-key",201);
        mvc.perform(post("/api/orders").with(buyer(8201)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":16001,\"quantity\":2,\"requestId\":\"same-key\"}"))
                .andExpect(status().isConflict());
        direct(8201,"same-key",200);direct(8201,"same-key",429);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trade_order",Integer.class));
        assertEquals(19,jdbc.queryForObject("SELECT stock FROM product WHERE id=16001",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_timeout_outbox",Integer.class));
    }

    @Test void authenticationCsrfAndInvalidBodiesAreRejectedBeforeQuota()throws Exception {
        mvc.perform(post("/api/orders").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("anonymous"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders").with(buyer(8201)).contentType(MediaType.APPLICATION_JSON).content(body("no-csrf"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders").with(buyer(8201)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{bad-json")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").with(buyer(8201)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders/checkout").with(buyer(8201)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[],\"addressId\":17001,\"requestId\":\"bad\"}")).andExpect(status().isBadRequest());
        assertFalse(redis.hasKey(key(8201)));
        for(int i=0;i<3;i++)direct(8201,"valid-"+i,201);
        direct(8201,"valid-blocked",429);
    }

    @Test void forgedUserIdCannotResetOrRedirectTheQuota()throws Exception {
        for(int i=0;i<3;i++)mvc.perform(post("/api/orders").with(buyer(8201)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":16001,\"quantity\":1,\"requestId\":\"forged-"+i+"\",\"userId\":"+(8202+i)+"}"))
                .andExpect(status().isCreated());
        direct(8201,"forged-blocked",429);assertFalse(redis.hasKey(key(8202)));
    }

    @Test void exhaustedCreationQuotaDoesNotBlockQueriesPaymentOrCancellation()throws Exception {
        for(int i=0;i<3;i++)direct(8201,"actions-"+i,201);
        direct(8201,"actions-blocked",429);
        var ids=jdbc.queryForList("SELECT id FROM trade_order WHERE user_id=8201 ORDER BY id",Long.class);
        mvc.perform(get("/api/orders").with(buyer(8201))).andExpect(status().isOk());
        mvc.perform(post("/api/orders/"+ids.get(0)+"/pay").with(buyer(8201)).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/orders/"+ids.get(1)+"/cancel").with(buyer(8201)).with(csrf())).andExpect(status().isOk());
        assertEquals("3",redis.opsForValue().get(key(8201)));
    }

    @Test void loggingInAgainDoesNotResetQuotaForTheSameUser()throws Exception {
        var first=login();
        for(int i=0;i<2;i++)mvc.perform(post("/api/orders").session(first).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("session-"+i))).andExpect(status().isCreated());
        mvc.perform(post("/api/auth/logout").session(first).with(csrf())).andExpect(status().isNoContent());
        var second=login();
        mvc.perform(post("/api/orders").session(second).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("session-third"))).andExpect(status().isCreated());
        mvc.perform(post("/api/orders").session(second).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("session-fourth"))).andExpect(status().isTooManyRequests());
        assertEquals("3",redis.opsForValue().get(key(8201)));
    }

    private void direct(long id,String requestId,int expected)throws Exception {
        mvc.perform(post("/api/orders").with(buyer(id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(requestId))).andExpect(status().is(expected));
    }
    private String body(String id){return "{\"productId\":16001,\"quantity\":1,\"requestId\":\""+id+"\"}";}
    private String key(long id){return PREFIX+":user:"+id;}
    private RequestPostProcessor buyer(long id){return user(new PlatformUserDetails(new AppUser(id,"rate_limit_"+id,"unused",id==8203?"ADMIN":"USER")));}
    private List<List<Map<String,Object>>> business(){return List.of("product","trade_order","cart_item","order_item","order_address","order_timeout_outbox","order_shipment").stream().map(t->jdbc.queryForList("SELECT * FROM "+t)).toList();}
    private MockHttpSession login()throws Exception {
        var r=mvc.perform(post("/api/auth/login").with(csrf()).param("username","rate_limit_a").param("password","RateLimitSession2026!"))
                .andExpect(status().isNoContent()).andReturn();
        var s=(MockHttpSession)r.getRequest().getSession(false);assertNotNull(s);sessions.add(s);return s;
    }
}
