package com.elaina.limitedtrade.order;

import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.user.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class CheckoutApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach void prepare() {
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (8001,'checkout_api_a','unused','USER'),(8002,'checkout_api_b','unused','USER')");
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (11001,'结算键盘',20,5,'ON_SALE'),(11002,'结算鼠标',7.50,4,'ON_SALE'),(11003,'保留商品',1,9,'ON_SALE')");
        jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (8001,11001,2),(8001,11002,3),(8001,11003,1),(8002,11001,1)");
        jdbc.update("INSERT INTO user_address(id,user_id,recipient_name,phone,province,city,district,detail_address,is_default) VALUES (12001,8001,'张三','13800138000','浙江','杭州','西湖','学院路1号',1),(12002,8002,'李四','13900139000','江苏','南京','鼓楼','测试路2号',1)");
    }

    @Test void checkoutCreatesOneOrderWithSnapshotsAndOnlyRemovesSelectedItems() throws Exception {
        long id=create();
        mvc.perform(get("/api/orders/"+id).with(buyer(8001)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAmount").value(62.50))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productId").value(11001))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineAmount").value(40))
                .andExpect(jsonPath("$.items[1].unitPrice").value(7.50))
                .andExpect(jsonPath("$.items[1].lineAmount").value(22.50))
                .andExpect(jsonPath("$.items[0].activityId").isEmpty())
                .andExpect(jsonPath("$.address.recipientName").value("张三"))
                .andExpect(jsonPath("$.address.detailAddress").value("学院路1号"))
                .andExpect(jsonPath("$.address.sourceAddressId").doesNotExist())
                .andExpect(jsonPath("$.items[0].orderId").doesNotExist())
                .andExpect(jsonPath("$.productId").doesNotExist()).andExpect(jsonPath("$.userId").doesNotExist());
        assertEquals(1,count("trade_order"));assertEquals(1,count("order_timeout_outbox"));
        assertEquals(3,stock(11001));assertEquals(1,stock(11002));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cart_item WHERE user_id=8001 AND product_id=11003",Integer.class));
        assertEquals(2,count("cart_item"));
        assertEquals(15,jdbc.queryForObject("SELECT TIMESTAMPDIFF(MINUTE,created_at,expires_at) FROM trade_order WHERE id=?",Integer.class,id));
    }

    @Test void retryIgnoresCartAndAddressChangesAndNormalizesProductOrder() throws Exception {
        long id=create();
        jdbc.update("DELETE FROM user_address WHERE id=12001");
        jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (8001,11001,4)");
        jdbc.update("UPDATE product SET price=99,name='改名' WHERE id=11001");
        mvc.perform(post("/api/orders/checkout").with(buyer(8001)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("[11002,11001]",12001,"checkout-one")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].productName").value("结算键盘"))
                .andExpect(jsonPath("$.totalAmount").value(62.50))
                .andExpect(jsonPath("$.address.recipientName").value("张三"));
        assertEquals(4,jdbc.queryForObject("SELECT quantity FROM cart_item WHERE user_id=8001 AND product_id=11001",Integer.class));
        assertEquals(1,count("trade_order"));assertEquals(1,count("order_timeout_outbox"));assertEquals(3,stock(11001));
    }

    @Test void changedProductsOrAddressCannotReuseRequestId() throws Exception {
        create();
        checkout(body("[11001]",12001,"checkout-one"),8001,409);
        checkout(body("[11001,11002]",12002,"checkout-one"),8001,409);
    }

    @Test void checkoutAndDirectEntryShareIdempotencyNamespace() throws Exception {
        create();
        mvc.perform(post("/api/orders").with(buyer(8001)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":11001,\"quantity\":2,\"requestId\":\"checkout-one\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/orders").with(buyer(8001)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":11001,\"quantity\":1,\"requestId\":\"direct-one\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.address").isEmpty());
        checkout(body("[11003]",12001,"direct-one"),8001,409);
    }

    @Test void failureOnSecondProductRollsBackFirstDeductionAndLeavesCart() throws Exception {
        jdbc.update("UPDATE product SET stock=2 WHERE id=11002");
        checkout(body("[11001,11002]",12001,"too-many"),8001,409);
        // 本类事务由测试框架在方法结束回滚，真实提交边界见CheckoutTransactionIntegrationTest。
        assertEquals(0,count("trade_order"));assertEquals(0,count("order_timeout_outbox"));assertEquals(4,count("cart_item"));
    }

    @Test void offSaleProductCannotBeCheckedOut() throws Exception {
        jdbc.update("UPDATE product SET status='OFF_SALE' WHERE id=11002");
        checkout(body("[11001,11002]",12001,"off-sale"),8001,409);
        assertEquals(4,count("cart_item"));
    }

    @Test void missingSelectedCartItemDoesNotCreatePartialOrder() throws Exception {
        checkout(body("[11001,11003]",12002,"missing-cart"),8002,409);
        assertEquals(0,count("trade_order"));assertEquals(5,stock(11001));
    }

    @Test void foreignAndMissingAddressesAreNotFound() throws Exception {
        checkout(body("[11001]",12002,"foreign-address"),8001,404);
        checkout(body("[11001]",999999,"missing-address"),8001,404);
        assertEquals(0,count("trade_order"));assertEquals(5,stock(11001));
    }

    @Test void snapshotsRemainAfterProductAndAddressChanges() throws Exception {
        long id=create();
        jdbc.update("UPDATE product SET name='更名',price=999 WHERE id=11001");
        jdbc.update("UPDATE user_address SET recipient_name='王五',detail_address='新地址' WHERE id=12001");
        mvc.perform(get("/api/orders/"+id).with(buyer(8001))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productName").value("结算键盘"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(20))
                .andExpect(jsonPath("$.address.recipientName").value("张三"));
        mvc.perform(get("/api/orders/"+id).with(buyer(8002))).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders").with(buyer(8002))).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/orders").with(buyer(8001))).andExpect(jsonPath("$.items[0].items.length()").value(2));
        mvc.perform(get("/api/admin/orders").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].items.length()").value(2))
                .andExpect(jsonPath("$.items[0].address").doesNotExist());
    }

    @Test void cancelRestoresAllItemsOnceAndDoesNotRefillCart() throws Exception {
        long id=create();
        mvc.perform(post("/api/orders/"+id+"/cancel").with(buyer(8001)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(post("/api/orders/"+id+"/cancel").with(buyer(8001)).with(csrf())).andExpect(status().isOk());
        assertEquals(5,stock(11001));assertEquals(4,stock(11002));assertEquals(2,count("cart_item"));
    }

    @Autowired OrderService orders;

    @Test void timeoutRestoresAllItemsOnceAndPayDoesNotDeductAgain() throws Exception {
        long id=create();
        var cutoff=java.time.LocalDateTime.now().withNano(0);
        jdbc.update("UPDATE trade_order SET expires_at=? WHERE id=?",cutoff,id);
        assertTrue(orders.closeExpiredOrder(id,cutoff));assertFalse(orders.closeExpiredOrder(id,cutoff));
        assertEquals(5,stock(11001));assertEquals(4,stock(11002));
        mvc.perform(post("/api/orders/"+id+"/pay").with(buyer(8001)).with(csrf())).andExpect(status().isConflict());
    }

    @Test void payKeepsAllDeductionsAndPreventsCancellation() throws Exception {
        long id=create();
        mvc.perform(post("/api/orders/"+id+"/pay").with(buyer(8001)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(post("/api/orders/"+id+"/pay").with(buyer(8001)).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/orders/"+id+"/cancel").with(buyer(8001)).with(csrf())).andExpect(status().isConflict());
        assertFalse(orders.closeExpiredOrder(id,java.time.LocalDateTime.now().plusHours(1)));
        assertEquals(3,stock(11001));assertEquals(1,stock(11002));
    }

    @ParameterizedTest
    @ValueSource(strings={"{}","{\"productIds\":[],\"addressId\":12001,\"requestId\":\"bad\"}",
            "{\"productIds\":[11001,11001],\"addressId\":12001,\"requestId\":\"bad\"}",
            "{\"productIds\":[null],\"addressId\":12001,\"requestId\":\"bad\"}",
            "{\"productIds\":[0],\"addressId\":12001,\"requestId\":\"bad\"}",
            "{\"productIds\":[11001.5],\"addressId\":12001,\"requestId\":\"bad\"}",
            "{\"productIds\":[11001],\"addressId\":-1,\"requestId\":\"bad\"}",
            "{\"productIds\":[11001],\"addressId\":12001,\"requestId\":\"空 格\"}"})
    void invalidInputIsRejectedBeforeWrites(String body) throws Exception {
        checkout(body,8001,400);assertEquals(5,stock(11001));assertEquals(0,count("trade_order"));
    }

    @Test void tooManyProductKindsAreRejected() throws Exception {
        String ids=java.util.stream.LongStream.rangeClosed(1,51).mapToObj(Long::toString).collect(java.util.stream.Collectors.joining(",","[","]"));
        checkout(body(ids,12001,"too-large"),8001,400);
    }

    @Test void authenticationAndCsrfAreRequired() throws Exception {
        mvc.perform(post("/api/orders/checkout").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body("[11001]",12001,"anonymous"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders/checkout").with(buyer(8001)).contentType(MediaType.APPLICATION_JSON)
                .content(body("[11001]",12001,"no-csrf"))).andExpect(status().isForbidden());
    }

    @Test void clientOwnershipPricesAndActivityDoNotOverrideServerValues() throws Exception {
        mvc.perform(post("/api/orders/checkout").with(buyer(8001)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productIds\":[11001],\"addressId\":12001,\"requestId\":\"forged\",\"userId\":8002,\"totalAmount\":0.01,\"activityId\":1,\"quantity\":99}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.totalAmount").value(40))
                .andExpect(jsonPath("$.items[0].activityId").isEmpty());
        assertEquals(8001,jdbc.queryForObject("SELECT user_id FROM trade_order WHERE request_id='forged'",Integer.class));
    }

    @Test void adminCheckoutStillUsesOnlyOwnCartAndAddress() throws Exception {
        var admin=user(new PlatformUserDetails(new AppUser(8001L,"checkout_admin","unused","ADMIN")));
        mvc.perform(post("/api/orders/checkout").with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body("[11001]",12002,"admin-foreign"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/checkout").with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body("[11001]",12001,"admin-owned"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].quantity").value(2));
        assertEquals(1,jdbc.queryForObject("SELECT quantity FROM cart_item WHERE user_id=8002 AND product_id=11001",Integer.class));
    }

    private long create() throws Exception {
        String response=mvc.perform(post("/api/orders/checkout").with(buyer(8001)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body("[11001,11002]",12001,"checkout-one"))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }
    private void checkout(String body,long userId,int status) throws Exception {
        mvc.perform(post("/api/orders/checkout").with(buyer(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status));
    }
    private String body(String ids,long address,String key) {return "{\"productIds\":"+ids+",\"addressId\":"+address+",\"requestId\":\""+key+"\"}";}
    private RequestPostProcessor buyer(long id) {return user(new PlatformUserDetails(new AppUser(id,"checkout_"+id,"unused","USER")));}
    private int stock(long id) {return jdbc.queryForObject("SELECT stock FROM product WHERE id=?",Integer.class,id);}
    private int count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
}
