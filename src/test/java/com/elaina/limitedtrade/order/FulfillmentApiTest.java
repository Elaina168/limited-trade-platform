package com.elaina.limitedtrade.order;

import java.util.List;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FulfillmentApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private final String shipping="{\"carrier\":\"  测试承运方  \",\"trackingNo\":\"  TRACK-001  \"}";

    @BeforeEach void prepare(){
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (8101,'fulfillment_api_a','unused','USER'),(8102,'fulfillment_api_b','unused','USER')");
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (13001,'履约测试商品',20,7,'ON_SALE')");
        String[] statuses={"PAID","PENDING_PAYMENT","PAID","CANCELED","CLOSED","PAID"};
        for(int i=0;i<statuses.length;i++){
            long id=14001+i,userId=i==5?8102:8101;
            jdbc.update("INSERT INTO trade_order(id,user_id,order_type,total_amount,status,request_id,created_at,expires_at,paid_at) VALUES (?,?,'CART',20,?,?,DATE_SUB(NOW(),INTERVAL 20 MINUTE),DATE_SUB(NOW(),INTERVAL 5 MINUTE),?)",id,userId,statuses[i],"fulfillment-"+id,statuses[i].equals("PAID")?java.time.LocalDateTime.now().minusMinutes(10).withNano(0):null);
            jdbc.update("INSERT INTO order_item(order_id,product_id,product_name,unit_price,quantity,line_amount) VALUES (?,13001,'履约成交名称',20,1,20)",id);
            if(i!=2)jdbc.update("INSERT INTO order_address(order_id,source_address_id,recipient_name,phone,province,city,district,detail_address) VALUES (?,12001,'张三','13800138000','浙江','杭州','西湖','学院路1号')",id);
        }
        jdbc.update("UPDATE trade_order SET order_type='DIRECT' WHERE id=14003");
    }

    @Test void adminDetailShowsOrderSnapshotForShipping() throws Exception {
        mvc.perform(get("/api/admin/orders/14001").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(14001)).andExpect(jsonPath("$.address.recipientName").value("张三"))
                .andExpect(jsonPath("$.items[0].productName").value("履约成交名称"))
                .andExpect(jsonPath("$.shipment").isEmpty());
    }

    @Test void adminShipsPaidOrderWithoutChangingStockAndOwnerCanSeeShipment() throws Exception {
        ship(14001,shipping,200);
        mvc.perform(get("/api/orders/14001").with(buyer(8101))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.shipment.carrier").value("测试承运方"))
                .andExpect(jsonPath("$.shipment.trackingNo").value("TRACK-001"))
                .andExpect(jsonPath("$.shipment.shippedAt").isNotEmpty())
                .andExpect(jsonPath("$.shipment.receivedAt").isEmpty())
                .andExpect(jsonPath("$.shipment.orderId").doesNotExist())
                .andExpect(jsonPath("$.endedAt").isEmpty());
        assertEquals(7,stock());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_shipment WHERE order_id=14001",Integer.class));
        mvc.perform(get("/api/orders/14001").with(buyer(8102))).andExpect(status().isNotFound());
    }

    @Test void sameShippingRetryPreservesTimeAndDifferentDetailsConflictEvenAfterReceipt() throws Exception {
        ship(14001,shipping,200);
        var original=jdbc.queryForMap("SELECT * FROM order_shipment WHERE order_id=14001");
        ship(14001,"{\"carrier\":\"测试承运方\",\"trackingNo\":\"TRACK-001\"}",200);
        assertEquals(original,jdbc.queryForMap("SELECT * FROM order_shipment WHERE order_id=14001"));
        ship(14001,"{\"carrier\":\"另一承运方\",\"trackingNo\":\"TRACK-001\"}",409);
        ship(14001,"{\"carrier\":\"测试承运方\",\"trackingNo\":\"TRACK-002\"}",409);
        receive(14001,8101,200);
        var completed=jdbc.queryForMap("SELECT * FROM order_shipment WHERE order_id=14001");
        ship(14001,shipping,200);receive(14001,8101,200);
        assertEquals(completed,jdbc.queryForMap("SELECT * FROM order_shipment WHERE order_id=14001"));
        ship(14001,"{\"carrier\":\"测试承运方\",\"trackingNo\":\"OTHER\"}",409);
    }

    @Test void buyerConfirmsReceiptAndNewStatusesCanBeFiltered() throws Exception {
        ship(14001,shipping,200);
        mvc.perform(get("/api/admin/orders").with(admin()).param("status","SHIPPED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].shipment.trackingNo").value("TRACK-001"))
                .andExpect(jsonPath("$.items[0].address").doesNotExist());
        receive(14001,8101,200);
        mvc.perform(get("/api/orders/14001").with(buyer(8101))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.shipment.receivedAt").isNotEmpty())
                .andExpect(jsonPath("$.endedAt").isEmpty());
        mvc.perform(get("/api/admin/orders").with(admin()).param("status","COMPLETED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/orders").with(buyer(8101))).andExpect(jsonPath("$[4].shipment.trackingNo").value("TRACK-001"));
        assertEquals(7,stock());
    }

    @ParameterizedTest @ValueSource(longs={14002,14004,14005})
    void invalidStatesCannotShip(long id)throws Exception {ship(id,shipping,409);}

    @Test void paidOrderWithoutAddressCannotShip()throws Exception {
        ship(14003,shipping,409);
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM trade_order WHERE id=14003",String.class));
    }

    @ParameterizedTest @ValueSource(longs={14001,14002,14003,14004,14005})
    void unshippedOrderCannotBeReceived(long id)throws Exception {receive(id,8101,409);}

    @Test void ownerScopeAndAdminRoleAreEnforced()throws Exception {
        ship(14001,shipping,200);
        receive(14001,8102,404);receive(14006,8101,404);receive(999999,8101,404);
        mvc.perform(post("/api/orders/14001/receive").with(user(new PlatformUserDetails(new AppUser(8102L,"admin_other","unused","ADMIN")))).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/orders/14001").with(buyer(8101))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/orders/14001/ship").with(buyer(8101)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(shipping))
                .andExpect(status().isForbidden());
    }

    @Test void missingAdminTargetsAreNotFound()throws Exception {
        ship(999999,shipping,404);
        mvc.perform(get("/api/admin/orders/999999").with(admin())).andExpect(status().isNotFound());
    }

    @ParameterizedTest @ValueSource(strings={"{}","{\"carrier\":\" \",\"trackingNo\":\"T\"}","{\"carrier\":\"测试\",\"trackingNo\":\" \"}","{\"carrier\":\"测试\"}"})
    void invalidShipmentInputIsRejected(String body)throws Exception {ship(14001,body,400);}

    @Test void overlongShipmentTextIsRejected()throws Exception {
        ship(14001,json.writeValueAsString(java.util.Map.of("carrier","承".repeat(51),"trackingNo","T")),400);
        ship(14001,json.writeValueAsString(java.util.Map.of("carrier","测试","trackingNo","T".repeat(65))),400);
    }

    @ParameterizedTest @ValueSource(strings={"carrier","trackingNo"})
    void unicodeWhitespaceCannotBecomeAnEmptyStoredShippingField(String field)throws Exception {
        String blank="\u3000\u2003";
        String body=json.writeValueAsString(java.util.Map.of(
                "carrier",field.equals("carrier")?blank:"测试承运方",
                "trackingNo",field.equals("trackingNo")?blank:"TRACK-001"));
        ship(14001,body,400);
        assertEquals("PAID",jdbc.queryForObject("SELECT status FROM trade_order WHERE id=14001",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_shipment WHERE order_id=14001",Integer.class));
    }

    @Test void anonymousAndMissingCsrfAreRejected()throws Exception {
        mvc.perform(get("/api/admin/orders/14001")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/orders/14001/ship").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(shipping)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/orders/14001/ship").with(admin()).contentType(MediaType.APPLICATION_JSON).content(shipping)).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/14001/receive").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders/14001/receive").with(buyer(8101))).andExpect(status().isForbidden());
    }

    @Test void invalidPathsAreRejected()throws Exception {
        ship(-1,shipping,400);receive(-1,8101,400);
        mvc.perform(get("/api/admin/orders/-1").with(admin())).andExpect(status().isBadRequest());
    }

    @Autowired OrderService orders;
    @Test void shippingAndCompletionCannotBeCanceledPaidOrClosedAgain()throws Exception {
        ship(14001,shipping,200);
        for(String action:List.of("pay","cancel"))mvc.perform(post("/api/orders/14001/"+action).with(buyer(8101)).with(csrf())).andExpect(status().isConflict());
        assertFalse(orders.closeExpiredOrder(14001,java.time.LocalDateTime.now().plusDays(1)));
        receive(14001,8101,200);
        for(String action:List.of("pay","cancel"))mvc.perform(post("/api/orders/14001/"+action).with(buyer(8101)).with(csrf())).andExpect(status().isConflict());
        assertFalse(orders.closeExpiredOrder(14001,java.time.LocalDateTime.now().plusDays(1)));assertEquals(7,stock());
    }

    private void ship(long id,String body,int expected)throws Exception {
        mvc.perform(post("/api/admin/orders/"+id+"/ship").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expected));
    }
    private void receive(long id,long userId,int expected)throws Exception {
        mvc.perform(post("/api/orders/"+id+"/receive").with(buyer(userId)).with(csrf())).andExpect(status().is(expected));
    }
    private RequestPostProcessor admin(){return user("fulfillment_admin").roles("ADMIN");}
    private RequestPostProcessor buyer(long id){return user(new PlatformUserDetails(new AppUser(id,"fulfillment_"+id,"unused","USER")));}
    private int stock(){return jdbc.queryForObject("SELECT stock FROM product WHERE id=13001",Integer.class);}
}
