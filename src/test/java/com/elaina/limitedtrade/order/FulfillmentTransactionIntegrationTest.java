package com.elaina.limitedtrade.order;

import java.util.*;
import java.time.LocalDateTime;
import com.elaina.limitedtrade.address.AddressService;
import com.elaina.limitedtrade.address.SaveAddressRequest;
import com.elaina.limitedtrade.product.ProductService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="app.product-cache.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FulfillmentTransactionIntegrationTest {
    private static final String KEY="limited-trade:test:fulfillment:"+UUID.randomUUID();
    @DynamicPropertySource static void cache(DynamicPropertyRegistry r){r.add("app.product-cache.key",()->KEY);}
    @Autowired OrderService orders;
    @Autowired AddressService addresses;
    @Autowired ProductService products;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    private boolean owned;
    private long orderId;
    private final List<MockHttpSession> sessions=new ArrayList<>();
    private final ShipOrderRequest shipping=new ShipOrderRequest("测试承运方","SHIP-001");

    @BeforeEach void prepare(){
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id BETWEEN 8111 AND 8113",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id IN (13011,13012)",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE id=15011",Integer.class));
        owned=true;
        String hash=passwords.encode("FulfillmentPersistence2026!");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (8111,'fulfillment_persist',?,'USER'),(8112,'fulfillment_other',?,'USER'),(8113,'fulfillment_admin',?,'ADMIN')",hash,hash,hash);
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (13011,'履约键盘',20,5,'ON_SALE'),(13012,'履约鼠标',7.50,4,'ON_SALE')");
        jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (8111,13011,2),(8111,13012,1)");
        jdbc.update("INSERT INTO user_address(id,user_id,recipient_name,phone,province,city,district,detail_address) VALUES (15011,8111,'张三','13800138000','浙江','杭州','西湖','学院路1号')");
        orderId=orders.checkout(8111,new CheckoutRequest(List.of(13011L,13012L),15011L,"fulfillment-persist")).order().getId();
        orders.payOrder(8111,orderId);
        products.listOnSaleProducts();assertNotNull(redis.opsForValue().get(KEY));
    }
    @AfterEach void cleanup(){
        for(var s:sessions)if(!s.isInvalid())s.invalidate();redis.delete(KEY);
        if(owned){
            jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN (SELECT id FROM trade_order WHERE user_id=8111)");
            jdbc.update("DELETE FROM trade_order WHERE user_id=8111");
            jdbc.update("DELETE FROM cart_item WHERE user_id=8111");
            jdbc.update("DELETE FROM user_address WHERE user_id=8111");
            jdbc.update("DELETE FROM product WHERE id IN (13011,13012)");
            jdbc.update("DELETE FROM app_user WHERE id BETWEEN 8111 AND 8113");
        }
    }

    @Test void shipmentInsertFailureRollsBackMainStatusAndPreservesOtherBusiness(){
        var before=business();String cached=redis.opsForValue().get(KEY);
        String trigger="test_fulfillment_insert_reject_8111";
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name=?",Integer.class,trigger));
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON order_shipment FOR EACH ROW BEGIN IF NEW.order_id="+orderId+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled shipment failure'; END IF; END");
        try {
            assertThrows(DataAccessException.class,()->orders.shipOrder(orderId,shipping));
            assertEquals("PAID",orderStatus());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_shipment WHERE order_id=?",Integer.class,orderId));
            assertEquals(before,business());assertEquals(cached,redis.opsForValue().get(KEY));
        } finally {jdbc.execute("DROP TRIGGER "+trigger);}
        assertEquals("SHIPPED",orders.shipOrder(orderId,shipping).getStatus());
    }

    @Test void receiptUpdateFailureRollsBackCompletedStatusAndPreservesShipment(){
        orders.shipOrder(orderId,shipping);
        var original=jdbc.queryForMap("SELECT * FROM order_shipment WHERE order_id=?",orderId);
        var before=business();String cached=redis.opsForValue().get(KEY);
        String trigger="test_fulfillment_receive_reject_8111";
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name=?",Integer.class,trigger));
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON order_shipment FOR EACH ROW BEGIN IF NEW.order_id="+orderId+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled receipt failure'; END IF; END");
        try {
            assertThrows(DataAccessException.class,()->orders.receiveOrder(8111,orderId));
            assertEquals("SHIPPED",orderStatus());assertEquals(original,jdbc.queryForMap("SELECT * FROM order_shipment WHERE order_id=?",orderId));
            assertEquals(before,business());assertEquals(cached,redis.opsForValue().get(KEY));
        } finally {jdbc.execute("DROP TRIGGER "+trigger);}
        assertEquals("COMPLETED",orders.receiveOrder(8111,orderId).getStatus());
    }

    @Test void fulfillmentAndRetriesChangeOnlyOrderStateAndShipment(){
        var before=business();String cached=redis.opsForValue().get(KEY);
        var shipped=orders.shipOrder(orderId,shipping).getShipment();
        assertEquals(shipped,orders.shipOrder(orderId,new ShipOrderRequest(" 测试承运方 "," SHIP-001 ")).getShipment());
        var received=orders.receiveOrder(8111,orderId).getShipment();
        assertNotNull(received.receivedAt());assertFalse(received.receivedAt().isBefore(received.shippedAt()));
        assertEquals(received,orders.receiveOrder(8111,orderId).getShipment());
        assertEquals(received,orders.shipOrder(orderId,shipping).getShipment());
        assertEquals(before,business());assertEquals(cached,redis.opsForValue().get(KEY));
        assertNull(jdbc.queryForObject("SELECT ended_at FROM trade_order WHERE id=?",LocalDateTime.class,orderId));
    }

    @Test void adminShippingUsesOrderAddressSnapshotAndNewSessionsKeepFulfillment()throws Exception {
        addresses.updateAddress(8111,15011,new SaveAddressRequest("李四","13900139000","江苏","南京","鼓楼","新地址"));
        addresses.deleteAddress(8111,15011);
        var admin=login("fulfillment_admin");
        mvc.perform(get("/api/admin/orders/"+orderId).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.address.recipientName").value("张三"));
        mvc.perform(post("/api/admin/orders/"+orderId+"/ship").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"carrier\":\"测试承运方\",\"trackingNo\":\"SHIP-001\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
        mvc.perform(post("/api/auth/logout").session(admin).with(csrf())).andExpect(status().isNoContent());
        var buyer=login("fulfillment_persist");
        mvc.perform(post("/api/orders/"+orderId+"/receive").session(buyer).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/api/auth/logout").session(buyer).with(csrf())).andExpect(status().isNoContent());
        var again=login("fulfillment_persist");
        mvc.perform(get("/api/orders/"+orderId).session(again)).andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.trackingNo").value("SHIP-001"))
                .andExpect(jsonPath("$.shipment.receivedAt").isNotEmpty());
        var other=login("fulfillment_other");
        mvc.perform(get("/api/orders/"+orderId).session(other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/orders/"+orderId).session(other)).andExpect(status().isForbidden());
    }

    private List<List<Map<String,Object>>> business(){return List.of("product","activity","cart_item","user_address","order_timeout_outbox","order_item","order_address").stream().map(t->jdbc.queryForList("SELECT * FROM "+t)).toList();}
    private String orderStatus(){return jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?",String.class,orderId);}
    private MockHttpSession login(String username)throws Exception {
        var result=mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password","FulfillmentPersistence2026!"))
                .andExpect(status().isNoContent()).andReturn();
        var s=(MockHttpSession)result.getRequest().getSession(false);assertNotNull(s);sessions.add(s);return s;
    }
}
