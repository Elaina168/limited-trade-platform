package com.elaina.limitedtrade.order;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import com.elaina.limitedtrade.address.AddressService;
import com.elaina.limitedtrade.address.SaveAddressRequest;
import com.elaina.limitedtrade.product.ProductService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="app.product-cache.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckoutTransactionIntegrationTest {
    private static final String KEY="limited-trade:test:checkout:"+UUID.randomUUID();
    @DynamicPropertySource static void cache(DynamicPropertyRegistry r){r.add("app.product-cache.key",()->KEY);}
    @Autowired OrderService orders;
    @Autowired AddressService addresses;
    @Autowired ProductService products;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    private boolean owned;
    private final List<MockHttpSession> sessions=new ArrayList<>();
    private final CheckoutRequest request=new CheckoutRequest(List.of(11011L,11012L),12011L,"transaction-checkout");

    @BeforeEach void prepare(){
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id IN (8011,8012)",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id BETWEEN 11011 AND 11015",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE id=12011",Integer.class));
        owned=true;
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (8011,'checkout_persist',?,'USER'),(8012,'checkout_other',?,'USER')",passwords.encode("CheckoutPersistence2026!"),passwords.encode("CheckoutPersistence2026!"));
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (11011,'事务键盘',20,5,'ON_SALE'),(11012,'事务鼠标',7.50,4,'ON_SALE')");
        jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (8011,11011,2),(8011,11012,3)");
        jdbc.update("INSERT INTO user_address(id,user_id,recipient_name,phone,province,city,district,detail_address,is_default) VALUES (12011,8011,'张三','13800138000','浙江','杭州','西湖','学院路1号',1)");
        redis.delete(KEY);
    }
    @AfterEach void cleanup(){
        for(var session:sessions)if(!session.isInvalid())session.invalidate();
        redis.delete(KEY);
        if(owned){
            jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN (SELECT id FROM trade_order WHERE user_id=8011)");
            jdbc.update("DELETE FROM trade_order WHERE user_id=8011");
            jdbc.update("DELETE FROM cart_item WHERE user_id=8011");
            jdbc.update("DELETE FROM user_address WHERE user_id=8011");
            jdbc.update("DELETE FROM product WHERE id BETWEEN 11011 AND 11015");
            jdbc.update("DELETE FROM app_user WHERE id IN (8011,8012)");
        }
    }

    @ParameterizedTest @ValueSource(strings={"stock","off-sale"})
    void secondProductFailureRollsBackEarlierStockAtRealTransactionBoundary(String reason){
        jdbc.update(reason.equals("stock")?"UPDATE product SET stock=2 WHERE id=11012":"UPDATE product SET status='OFF_SALE' WHERE id=11012");
        products.listOnSaleProducts();String cached=redis.opsForValue().get(KEY);assertNotNull(cached);
        assertThrows(ResponseStatusException.class,()->orders.checkout(8011,request));
        assertEquals(5,stock(11011));assertEquals(reason.equals("stock")?2:4,stock(11012));
        assertNoOrder();assertEquals(cached,redis.opsForValue().get(KEY));
    }

    @ParameterizedTest @ValueSource(strings={"order_item","order_address","order_timeout_outbox","cart_item"})
    void realSqlFailureRollsBackAllCheckoutWritesAndDoesNotEvictCache(String table){
        products.listOnSaleProducts();String cached=redis.opsForValue().get(KEY);
        String trigger="test_checkout_reject_8011";
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name=?",Integer.class,trigger));
        String event=table.equals("cart_item")?"DELETE":"INSERT";
        String condition=table.equals("cart_item")?"OLD.user_id=8011":"(SELECT user_id FROM trade_order WHERE id=NEW.order_id)=8011";
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE "+event+" ON "+table+" FOR EACH ROW BEGIN IF "+condition+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled checkout failure'; END IF; END");
        try {
            assertThrows(DataAccessException.class,()->orders.checkout(8011,request));
            assertEquals(5,stock(11011));assertEquals(4,stock(11012));assertNoOrder();
            assertEquals(cached,redis.opsForValue().get(KEY));
        } finally {jdbc.execute("DROP TRIGGER "+trigger);}
    }

    @ParameterizedTest @ValueSource(strings={"cancel","close"})
    void failureWhileReturningSecondItemRollsBackStatusAndAllReturnedStock(String action){
        long id=orders.checkout(8011,request).order().getId();
        var cutoff=LocalDateTime.now().withNano(0);
        jdbc.update("UPDATE trade_order SET expires_at=? WHERE id=?",cutoff,id);
        products.listOnSaleProducts();String cached=redis.opsForValue().get(KEY);
        String trigger="test_checkout_restore_reject_8011";
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name=?",Integer.class,trigger));
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON product FOR EACH ROW BEGIN IF OLD.id=11012 AND NEW.stock>OLD.stock THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled restore failure'; END IF; END");
        try {
            assertThrows(DataAccessException.class,()->{if(action.equals("cancel"))orders.cancelOrder(8011,id);else orders.closeExpiredOrder(id,cutoff);});
            assertEquals("PENDING_PAYMENT",jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?",String.class,id));
            assertNull(jdbc.queryForObject("SELECT ended_at FROM trade_order WHERE id=?",LocalDateTime.class,id));
            assertEquals(3,stock(11011));assertEquals(1,stock(11012));assertEquals(cached,redis.opsForValue().get(KEY));
        } finally {jdbc.execute("DROP TRIGGER "+trigger);}
        if(action.equals("cancel"))orders.cancelOrder(8011,id);else assertTrue(orders.closeExpiredOrder(id,cutoff));
        assertEquals(5,stock(11011));assertEquals(4,stock(11012));assertFalse(redis.hasKey(KEY));
    }

    @Test void successfulCommitEvictsCacheButIdempotentRetryDoesNot(){
        products.listOnSaleProducts();assertTrue(redis.hasKey(KEY));
        long id=orders.checkout(8011,request).order().getId();assertFalse(redis.hasKey(KEY));
        products.listOnSaleProducts();String cached=redis.opsForValue().get(KEY);
        addresses.deleteAddress(8011,12011);
        assertEquals(id,orders.checkout(8011,request).order().getId());
        assertEquals(cached,redis.opsForValue().get(KEY));
        orders.cancelOrder(8011,id);assertFalse(redis.hasKey(KEY));
    }

    @Test void validIndividualAmountsWhoseTotalExceedsDatabaseCapacityAreRejectedAndRolledBack(){
        jdbc.update("UPDATE product SET price=99999999.99,stock=2147483647 WHERE id IN (11011,11012)");
        jdbc.update("UPDATE cart_item SET quantity=2147483647 WHERE user_id=8011");
        for(long id=11013;id<=11015;id++){
            jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (?,'金额边界商品',99999999.99,2147483647,'ON_SALE')",id);
            jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (8011,?,2147483647)",id);
        }
        var error=assertThrows(ResponseStatusException.class,()->orders.checkout(8011,
                new CheckoutRequest(List.of(11011L,11012L,11013L,11014L,11015L),12011L,"amount-overflow")));
        assertEquals(409,error.getStatusCode().value());
        for(long id=11011;id<=11015;id++)assertEquals(2147483647,stock(id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE user_id=8011",Integer.class));
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM cart_item WHERE user_id=8011",Integer.class));
    }

    @Test void logoutAndNewSessionRetainCommittedOrderAndAddressSnapshot() throws Exception {
        var first=login("checkout_persist");
        mvc.perform(post("/api/orders/checkout").session(first).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productIds\":[11011,11012],\"addressId\":12011,\"requestId\":\"session-checkout\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(post("/api/auth/logout").session(first).with(csrf())).andExpect(status().isNoContent());
        addresses.updateAddress(8011,12011,new SaveAddressRequest("李四","13900139000","江苏","南京","鼓楼","新地址"));
        addresses.deleteAddress(8011,12011);
        var second=login("checkout_persist");
        mvc.perform(get("/api/orders").session(second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].items.length()").value(2))
                .andExpect(jsonPath("$[0].address.recipientName").value("张三"));
        mvc.perform(get("/api/cart/items").session(second)).andExpect(content().json("[]"));
        var other=login("checkout_other");mvc.perform(get("/api/orders").session(other)).andExpect(content().json("[]"));
    }

    private void assertNoOrder(){
        for(String table:List.of("trade_order","order_item","order_address","order_timeout_outbox"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM cart_item WHERE user_id=8011",Integer.class));
    }
    private int stock(long id){return jdbc.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,id);}
    private MockHttpSession login(String username)throws Exception {
        var result=mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password","CheckoutPersistence2026!"))
                .andExpect(status().isNoContent()).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);assertNotNull(session);sessions.add(session);return session;
    }
}
