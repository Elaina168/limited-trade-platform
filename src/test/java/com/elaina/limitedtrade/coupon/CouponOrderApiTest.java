package com.elaina.limitedtrade.coupon;

import java.time.LocalDateTime;
import java.util.*;
import com.elaina.limitedtrade.order.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static com.elaina.limitedtrade.coupon.CouponApiTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class CouponOrderApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired OrderService orders;
    final LocalDateTime now=LocalDateTime.now().withNano(0);

    @BeforeEach void prepare(){
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (8201,'coupon_order_a','unused','USER'),(8202,'coupon_order_b','unused','USER')");
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (16001,'活动键盘',129,10,'ON_SALE')");
        jdbc.update("INSERT INTO activity(id,product_id,name,total_quantity,remaining_quantity,starts_at,ends_at) VALUES (16001,16001,'限量活动',8,8,?,?),(16002,16001,'另一活动',8,8,?,?)",now.minusHours(2),now.plusHours(4),now.minusHours(2),now.plusHours(4));
        jdbc.update("INSERT INTO activity_coupon(id,activity_id,name,threshold_amount,discount_amount,total_quantity,remaining_quantity,claim_starts_at,claim_ends_at,use_starts_at,use_ends_at) VALUES (16011,16001,'满200减20',200,20,5,3,?,?,?,?)",now.minusHours(1),now.plusHours(1),now.minusMinutes(30),now.plusHours(2));
        jdbc.update("INSERT INTO user_coupon(id,coupon_id,user_id,status,claimed_at) VALUES (16021,16011,8201,'AVAILABLE',?),(16022,16011,8202,'AVAILABLE',?)",now,now);
    }
    Map<String,Object> body(String key){return new HashMap<>(Map.of("productId",16001,"quantity",2,"requestId",key,"activityId",16001,"userCouponId",16021));}
    long create(String key)throws Exception{
        var r=mvc.perform(post("/api/orders").with(buyer(8201)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body(key))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.originalAmount").value(258))
                .andExpect(jsonPath("$.discountAmount").value(20)).andExpect(jsonPath("$.totalAmount").value(238))
                .andExpect(jsonPath("$.coupon.userCouponId").value(16021)).andExpect(jsonPath("$.coupon.name").value("满200减20"))
                .andExpect(jsonPath("$.items[0].lineAmount").value(258)).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }
    void postBody(Map<String,Object> b,int status)throws Exception{
        mvc.perform(post("/api/orders").with(buyer(8201)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b))).andExpect(status().is(status));
    }
    String state(){return jdbc.queryForObject("SELECT status FROM user_coupon WHERE id=16021",String.class);}

    @Test void reservesCouponAndReturnsImmutableSnapshotOnRetryAndQueries()throws Exception{
        long id=create("coupon-create");assertEquals("RESERVED",state());
        assertEquals(8,jdbc.queryForObject("SELECT stock FROM product WHERE id=16001",Integer.class));
        assertEquals(6,jdbc.queryForObject("SELECT remaining_quantity FROM activity WHERE id=16001",Integer.class));
        jdbc.update("UPDATE product SET price=300,name='新商品名' WHERE id=16001");
        jdbc.update("UPDATE activity_coupon SET claim_ends_at=DATE_ADD(claim_starts_at,INTERVAL 1 SECOND),use_ends_at=? WHERE id=16011",now.minusSeconds(1));
        postBody(body("coupon-create"),200);
        mvc.perform(get("/api/orders/{id}",id).with(buyer(8201))).andExpect(jsonPath("$.totalAmount").value(238)).andExpect(jsonPath("$.coupon.name").value("满200减20"));
        mvc.perform(get("/api/admin/orders").with(admin())).andExpect(jsonPath("$.items[0].originalAmount").value(258)).andExpect(jsonPath("$.items[0].coupon.userCouponId").value(16021));
        var changed=body("coupon-create");changed.remove("userCouponId");postBody(changed,409);
    }
    @Test void expiryAfterOrderDoesNotChangePaymentAndCouponIsUsedOnlyOnce()throws Exception{
        long id=create("coupon-pay");
        jdbc.update("UPDATE activity_coupon SET claim_ends_at=DATE_ADD(claim_starts_at,INTERVAL 1 SECOND),use_ends_at=? WHERE id=16011",now.minusSeconds(1));
        mvc.perform(post("/api/orders/{id}/pay",id).with(buyer(8201)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.totalAmount").value(238));
        assertEquals("USED",state());
        mvc.perform(post("/api/orders/{id}/pay",id).with(buyer(8201)).with(csrf())).andExpect(status().isOk());
        postBody(body("coupon-pay-again"),409);
    }
    @Test void cancelReleasesCouponButPreservesOldSnapshotAndOldEventsCannotReleaseReuse()throws Exception{
        long id=create("coupon-cancel");orders.cancelOrder(8201,id);orders.cancelOrder(8201,id);
        assertEquals("AVAILABLE",state());assertNull(jdbc.queryForObject("SELECT order_id FROM user_coupon WHERE id=16021",Long.class));
        long second=create("coupon-reuse");
        assertFalse(orders.closeExpiredOrder(id,now.plusHours(1)));
        assertEquals(second,jdbc.queryForObject("SELECT order_id FROM user_coupon WHERE id=16021",Long.class));
        mvc.perform(get("/api/orders/{id}",id).with(buyer(8201))).andExpect(jsonPath("$.coupon.userCouponId").value(16021)).andExpect(jsonPath("$.discountAmount").value(20));
        assertEquals(3,jdbc.queryForObject("SELECT remaining_quantity FROM activity_coupon WHERE id=16011",Integer.class));
    }
    @Test void timeoutReleasesOnceAndExpiredCouponStaysUnusable()throws Exception{
        long id=create("coupon-close");
        jdbc.update("UPDATE trade_order SET expires_at=? WHERE id=?",now.minusSeconds(1),id);
        jdbc.update("UPDATE activity_coupon SET claim_ends_at=DATE_ADD(claim_starts_at,INTERVAL 1 SECOND),use_ends_at=? WHERE id=16011",now.minusSeconds(1));
        assertTrue(orders.closeExpiredOrder(id,now));assertFalse(orders.closeExpiredOrder(id,now));
        assertEquals("AVAILABLE",state());
        mvc.perform(get("/api/coupons/mine").with(buyer(8201))).andExpect(jsonPath("$[0].expired").value(true));
        postBody(body("expired-reuse"),409);
    }
    @ParameterizedTest @ValueSource(strings={"foreign","missing","differentActivity","noActivity","belowThreshold","future","expired","reserved"})
    void unusableCouponIsRejected(String mode)throws Exception{
        var b=body("invalid-"+mode);
        switch(mode){
            case "foreign"->b.put("userCouponId",16022);
            case "missing"->b.put("userCouponId",999999);
            case "differentActivity"->b.put("activityId",16002);
            case "noActivity"->b.remove("activityId");
            case "belowThreshold"->b.put("quantity",1);
            case "future"->jdbc.update("UPDATE activity_coupon SET use_starts_at=? WHERE id=16011",now.plusMinutes(10));
            case "expired"->jdbc.update("UPDATE activity_coupon SET claim_ends_at=DATE_ADD(claim_starts_at,INTERVAL 1 SECOND),use_ends_at=? WHERE id=16011",now.minusSeconds(1));
            case "reserved"->create("first");
        }
        postBody(b,mode.equals("foreign")||mode.equals("missing")?404:409);
    }
    @Test void ordinaryOrderHasZeroDiscountAndNoCoupon()throws Exception{
        var b=body("no-coupon");b.remove("userCouponId");
        mvc.perform(post("/api/orders").with(buyer(8201)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.originalAmount").value(258)).andExpect(jsonPath("$.discountAmount").value(0)).andExpect(jsonPath("$.coupon").isEmpty());
        b.put("userCouponId",-1);b.put("requestId","negative");postBody(b,400);
    }
}
