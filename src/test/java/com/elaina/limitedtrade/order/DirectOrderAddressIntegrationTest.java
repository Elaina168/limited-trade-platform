package com.elaina.limitedtrade.order;

import java.util.*;
import com.elaina.limitedtrade.address.AddressService;
import com.elaina.limitedtrade.address.SaveAddressRequest;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.coupon.CouponService;
import com.elaina.limitedtrade.user.AppUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class DirectOrderAddressIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired OrderService orders;
    @Autowired AddressService addresses;
    @Autowired CouponService coupons;
    boolean owned;
    long userCouponId;

    @BeforeEach void prepare(){
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id BETWEEN 8401 AND 8402",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=18001",Integer.class));
        owned=true;
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES(8401,'direct_address_a','unused','USER'),(8402,'direct_address_b','unused','USER')");
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES(18001,'活动地址商品',129,10,'ON_SALE')");
        jdbc.update("INSERT INTO activity(id,product_id,name,total_quantity,remaining_quantity,starts_at,ends_at) VALUES(18001,18001,'活动地址',8,8,DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 2 HOUR))");
        jdbc.update("INSERT INTO activity_coupon(id,activity_id,name,threshold_amount,discount_amount,total_quantity,remaining_quantity,claim_starts_at,claim_ends_at,use_starts_at,use_ends_at) VALUES(18011,18001,'满200减20',200,20,3,3,DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 HOUR),DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 2 HOUR))");
        userCouponId=coupons.claim(8401,18011).id();
        jdbc.update("INSERT INTO user_address(id,user_id,recipient_name,phone,province,city,district,detail_address) VALUES(18031,8401,'张三','13800138000','浙江','杭州','西湖','学院路1号'),(18032,8402,'李四','13900139000','江苏','南京','鼓楼','另一地址'),(18033,8401,'王五','13700137000','浙江','杭州','拱墅','第二地址')");
    }
    @AfterEach void cleanup(){
        if(owned){
            jdbc.update("DELETE FROM order_coupon WHERE coupon_id=18011");
            jdbc.update("DELETE FROM user_coupon WHERE coupon_id=18011");
            jdbc.update("DELETE FROM activity_coupon WHERE id=18011");
            jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN(SELECT id FROM trade_order WHERE user_id BETWEEN 8401 AND 8402)");
            jdbc.update("DELETE FROM trade_order WHERE user_id BETWEEN 8401 AND 8402");
            jdbc.update("DELETE FROM user_address WHERE user_id BETWEEN 8401 AND 8402");
            jdbc.update("DELETE FROM activity WHERE id=18001");
            jdbc.update("DELETE FROM product WHERE id=18001");
            jdbc.update("DELETE FROM app_user WHERE id BETWEEN 8401 AND 8402");
        }
    }
    RequestPostProcessor buyer(){return user(new PlatformUserDetails(new AppUser(8401L,"direct_address_a","unused","USER")));}
    RequestPostProcessor admin(){return user(new PlatformUserDetails(new AppUser(8402L,"direct_address_b","unused","ADMIN")));}
    Map<String,Object> body(String key){return new HashMap<>(Map.of("productId",18001,"quantity",2,"requestId",key,"activityId",18001,"userCouponId",userCouponId,"addressId",18031));}
    JsonNode submit(Map<String,Object> body,int code)throws Exception{
        var r=mvc.perform(post("/api/orders").with(buyer()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(code)).andReturn();
        return code==200||code==201?json.readTree(r.getResponse().getContentAsString()):null;
    }
    void unchanged(){
        assertEquals(10,jdbc.queryForObject("SELECT stock FROM product WHERE id=18001",Integer.class));
        assertEquals(8,jdbc.queryForObject("SELECT remaining_quantity FROM activity WHERE id=18001",Integer.class));
        assertEquals("AVAILABLE",jdbc.queryForObject("SELECT status FROM user_coupon WHERE id=?",String.class,userCouponId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE user_id=8401",Integer.class));
    }

    @Test void activityCouponAddressOrderCanBePaidShippedAndReceived()throws Exception{
        var order=submit(body("direct-address-flow"),201);
        assertEquals("张三",order.path("address").path("recipientName").asText());
        assertEquals(238,order.path("totalAmount").asInt());assertEquals(20,order.path("discountAmount").asInt());
        long id=order.path("id").asLong();
        assertEquals(18031L,jdbc.queryForObject("SELECT source_address_id FROM order_address WHERE order_id=?",Long.class,id));
        assertFalse(order.path("address").has("sourceAddressId"));
        mvc.perform(post("/api/orders/{id}/pay",id).with(buyer()).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/admin/orders/{id}",id).with(admin())).andExpect(status().isOk()).andExpect(jsonPath("$.address.detailAddress").value("学院路1号"));
        mvc.perform(post("/api/admin/orders/{id}/ship",id).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"carrier\":\"测试承运方\",\"trackingNo\":\"DIRECT-ADDRESS-001\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
        mvc.perform(post("/api/orders/{id}/receive",id).with(buyer()).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertEquals("USED",jdbc.queryForObject("SELECT status FROM user_coupon WHERE id=?",String.class,userCouponId));
    }
    @Test void snapshotAndRetrySurviveAddressModificationAndDeletion()throws Exception{
        var b=body("direct-address-snapshot");var first=submit(b,201);
        addresses.updateAddress(8401,18031,new SaveAddressRequest("新收货人","13800138000","江苏","南京","鼓楼","新地址"));
        assertEquals(first,submit(b,200));
        addresses.deleteAddress(8401,18031);
        assertEquals(first,submit(b,200));
        assertEquals(8,jdbc.queryForObject("SELECT stock FROM product WHERE id=18001",Integer.class));
        mvc.perform(get("/api/orders/{id}",first.path("id").asLong()).with(buyer())).andExpect(jsonPath("$.address.recipientName").value("张三"));
    }
    @ParameterizedTest @ValueSource(longs={18032,999999})
    void foreignOrMissingAddressRejectedWithoutResourceChanges(long addressId)throws Exception{
        var b=body("invalid-address");b.put("addressId",addressId);submit(b,404);unchanged();
    }
    @ParameterizedTest @ValueSource(longs={0,-1})
    void nonPositiveAddressRejectedBeforeBusinessWrites(long addressId)throws Exception{
        var b=body("nonpositive-address");b.put("addressId",addressId);submit(b,400);unchanged();
    }
    @Test void changingOrRemovingAddressOnSameRequestConflicts()throws Exception{
        var b=body("changed-address");submit(b,201);
        b.put("addressId",18033);submit(b,409);
        b.remove("addressId");submit(b,409);
        b.put("addressId",999999);submit(b,409);
    }
    @Test void oldRequestWithoutAddressRemainsValidAndCannotBeRetrofitted()throws Exception{
        var b=body("legacy-address");b.remove("addressId");
        var first=submit(b,201);assertTrue(first.path("address").isNull());assertEquals(first,submit(b,200));
        b.put("addressId",18031);submit(b,409);
        orders.payOrder(8401,first.path("id").asLong());
        mvc.perform(post("/api/admin/orders/{id}/ship",first.path("id").asLong()).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"carrier\":\"测试承运方\",\"trackingNo\":\"NO-ADDRESS\"}"))
                .andExpect(status().isConflict());
    }
    @Test void ordinaryDirectOrderCanReuseSameAddressSupport()throws Exception{
        var b=body("ordinary-address");b.remove("activityId");b.remove("userCouponId");
        var result=submit(b,201);assertEquals("张三",result.path("address").path("recipientName").asText());
        assertEquals(258,result.path("totalAmount").asInt());
        assertEquals(8,jdbc.queryForObject("SELECT remaining_quantity FROM activity WHERE id=18001",Integer.class));
    }
    @ParameterizedTest @ValueSource(strings={"address","outbox"})
    void failedSnapshotOrLaterWriteRollsBackAddressCouponAndResources(String target)throws Exception{
        String trigger="test_direct_address_reject_8401";
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name=?",Integer.class,trigger));
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON "+(target.equals("address")?"order_address":"order_timeout_outbox")+" FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled direct address failure'; END");
        try{
            var failure=assertThrows(jakarta.servlet.ServletException.class,()->submit(body("rollback-address"),201));
            assertInstanceOf(DataAccessException.class,failure.getCause());
            unchanged();
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_address WHERE source_address_id=18031",Integer.class));
        }finally{jdbc.execute("DROP TRIGGER "+trigger);}
        assertEquals("张三",submit(body("rollback-address"),201).path("address").path("recipientName").asText());
    }
}
