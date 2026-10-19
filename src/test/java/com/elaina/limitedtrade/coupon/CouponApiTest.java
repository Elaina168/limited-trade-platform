package com.elaina.limitedtrade.coupon;

import java.util.*;
import java.time.LocalDateTime;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.user.AppUser;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class CouponApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    final LocalDateTime now = LocalDateTime.now().withNano(0);

    @BeforeEach void prepare() {
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (8201,'coupon_api_a','unused','USER'),(8202,'coupon_api_b','unused','USER')");
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (16001,'活动键盘',129,10,'ON_SALE')");
        jdbc.update("INSERT INTO activity(id,product_id,name,total_quantity,remaining_quantity,starts_at,ends_at) VALUES (16001,16001,'测试限量活动',8,8,?,?)",now.minusHours(2),now.plusHours(4));
    }

    Map<String,Object> body() {
        return new HashMap<>(Map.of("activityId",16001,"name","活动满200减20", "thresholdAmount","200.00","discountAmount","20.00",
                "totalQuantity",2,"claimStartsAt",now.minusHours(1).toString(),"claimEndsAt",now.plusHours(1).toString(),
                "useStartsAt",now.minusMinutes(30).toString(),"useEndsAt",now.plusHours(2).toString()));
    }
    long create(Map<String,Object> body) throws Exception {
        var response=mvc.perform(post("/api/admin/coupons").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    }
    static RequestPostProcessor buyer(long id) {return user(new PlatformUserDetails(new AppUser(id,"coupon_"+id,"unused","USER")));}
    static RequestPostProcessor admin() {return user(new PlatformUserDetails(new AppUser(8201L,"coupon_admin","unused","ADMIN")));}

    @Test void createListsRulesAndClaimOnlyConsumesCouponQuota() throws Exception {
        long id=create(body());
        mvc.perform(get("/api/activities/16001/coupons")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].thresholdAmount").value(200)).andExpect(jsonPath("$[0].remainingQuantity").value(2));
        var first=mvc.perform(post("/api/coupons/{id}/claim",id).with(buyer(8201)).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE")).andExpect(jsonPath("$.coupon.id").value(id))
                .andExpect(jsonPath("$.expired").value(false)).andReturn();
        long owned=json.readTree(first.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/coupons/{id}/claim",id).with(buyer(8201)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owned));
        mvc.perform(get("/api/coupons/mine").with(buyer(8202))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/coupons/mine").with(buyer(8201))).andExpect(status().isOk()).andExpect(jsonPath("$[0].coupon.useEndsAt").isNotEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT remaining_quantity FROM activity_coupon WHERE id=?",Integer.class,id));
        assertEquals(10,jdbc.queryForObject("SELECT stock FROM product WHERE id=16001",Integer.class));
        assertEquals(8,jdbc.queryForObject("SELECT remaining_quantity FROM activity WHERE id=16001",Integer.class));
    }

    @ParameterizedTest @ValueSource(strings={"thresholdAmount","discountAmount","totalQuantity"})
    void nonPositiveValuesRejected(String field) throws Exception {
        var b=body();b.put(field,0);
        mvc.perform(post("/api/admin/coupons").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b))).andExpect(status().isBadRequest());
    }
    @ParameterizedTest @ValueSource(strings={"claimStartsAt","useStartsAt","useEndsAt","claimEndsAt","discountAmount","name"})
    void invalidRulesRejected(String field) throws Exception {
        var b=body();b.put(field,switch(field){case "claimStartsAt","useStartsAt"->now.plusHours(3).toString();case "useEndsAt"->now.plusHours(5).toString();case "claimEndsAt"->now.plusHours(3).toString();case "discountAmount"->"200";default->"\u2003";});
        mvc.perform(post("/api/admin/coupons").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b))).andExpect(status().isBadRequest());
    }
    @Test void quotaExhaustionAndMissingTargetsRejected() throws Exception {
        var b=body();b.put("totalQuantity",1);long id=create(b);
        mvc.perform(post("/api/coupons/{id}/claim",id).with(buyer(8201)).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/coupons/{id}/claim",id).with(buyer(8202)).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/coupons/999999/claim").with(buyer(8202)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/activities/999999/coupons")).andExpect(status().isNotFound());
        b.put("activityId",999999);mvc.perform(post("/api/admin/coupons").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b))).andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings={"future","ended","expired"})
    void cannotClaimOutsideClaimWindow(String mode) throws Exception {
        var b=body();
        if(mode.equals("future"))b.put("claimStartsAt",now.plusMinutes(30).toString());
        else {b.put("claimStartsAt",now.minusHours(2).toString());b.put("claimEndsAt",now.minusMinutes(1).toString());}
        if(mode.equals("expired")){b.put("useStartsAt",now.minusHours(2).toString());b.put("useEndsAt",now.minusSeconds(30).toString());}
        long id=create(b);
        mvc.perform(post("/api/coupons/{id}/claim",id).with(buyer(8201)).with(csrf())).andExpect(status().isConflict());
    }
    @Test void securityAndInputBoundaries() throws Exception {
        mvc.perform(post("/api/admin/coupons").with(buyer(8201)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/coupons").with(admin()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/coupons/mine")).andExpect(status().isUnauthorized());
        long id=create(body());
        mvc.perform(post("/api/coupons/{id}/claim",id).with(buyer(8201))).andExpect(status().isForbidden());
        mvc.perform(post("/api/coupons/-1/claim").with(buyer(8201)).with(csrf())).andExpect(status().isBadRequest());
    }
}
