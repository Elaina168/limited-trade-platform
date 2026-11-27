package com.elaina.limitedtrade.cart;

import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.user.AppUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/sql/cart-test-data.sql")
class CartApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void emptyCartReturnsArrayWithoutCreatingRecords() throws Exception {
        mvc.perform(get("/api/cart/items").with(buyer(7901)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        assertEquals(0, count());
    }

    @Test
    void firstPutCreatesAndReplaySetsAbsoluteQuantity() throws Exception {
        for (int i=0; i<2; i++) {
            mvc.perform(put("/api/cart/items/10101").with(buyer(7901)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}"))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));
        }
        assertEquals(1, count());
        assertEquals(3, quantity(7901, 10101));
        mvc.perform(put("/api/cart/items/10101").with(buyer(7901)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(status().isNoContent());
        assertEquals(5, quantity(7901, 10101));
        assertEquals(5, jdbc.queryForObject("SELECT stock FROM product WHERE id=10101", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_timeout_outbox", Integer.class));
    }

    @Test
    void addCreatesAndIncrementsOnlyTheAuthenticatedBuyersCart() throws Exception {
        jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (7902,10101,1)");
        mvc.perform(add(10101, 2).with(buyer(7901)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(10101))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.stock").value(5));
        mvc.perform(add(10101, 3).with(buyer(7901)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(5));
        assertEquals(2, count());
        assertEquals(5, quantity(7901, 10101));
        assertEquals(1, quantity(7902, 10101));
        assertEquals(5, jdbc.queryForObject("SELECT stock FROM product WHERE id=10101", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_timeout_outbox", Integer.class));
    }

    @Test
    void addAboveStockOrIntegerRangeLeavesTheCartUnchanged() throws Exception {
        jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (7901,10101,4)");
        for (int delta : new int[] {2, Integer.MAX_VALUE}) {
            mvc.perform(add(10101, delta).with(buyer(7901)).with(csrf()))
                    .andExpect(status().isConflict());
            assertEquals(4, quantity(7901, 10101));
        }
    }

    @Test
    void addRejectsMissingOffSaleAndSoldOutProducts() throws Exception {
        mvc.perform(add(10999, 1).with(buyer(7901)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(add(10102, 1).with(buyer(7901)).with(csrf())).andExpect(status().isConflict());
        mvc.perform(add(10103, 1).with(buyer(7901)).with(csrf())).andExpect(status().isConflict());
        assertEquals(0, count());
    }

    @Test
    void addRequiresAuthenticationCsrfAndPositiveInput() throws Exception {
        mvc.perform(add(10101, 1).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(add(10101, 1).with(buyer(7901))).andExpect(status().isForbidden());
        mvc.perform(add(10101, 0).with(buyer(7901)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(add(0, 1).with(buyer(7901)).with(csrf())).andExpect(status().isBadRequest());
        assertEquals(0, count());
    }

    @Test
    void listUsesAuthenticatedOwner() throws Exception {
        jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (7901,10101,2),(7902,10101,7)");
        mvc.perform(get("/api/cart/items").with(buyer(7901))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].productId").value(10101))
                .andExpect(jsonPath("$[0].quantity").value(2)).andExpect(jsonPath("$[0].userId").doesNotExist());
        mvc.perform(get("/api/cart/items").with(buyer(7902))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quantity").value(7));
    }

    @Test
    void forgedUserIdCannotChangeOwner() throws Exception {
        jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (7902,10101,7)");
        mvc.perform(put("/api/cart/items/10101").param("userId", "7902").with(buyer(7901)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3,\"userId\":7902}"))
                .andExpect(status().isNoContent());
        assertEquals(3, quantity(7901, 10101));
        assertEquals(7, quantity(7902, 10101));
        mvc.perform(get("/api/cart/items").param("userId", "7902").with(buyer(7901)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].quantity").value(3));
    }

    @Test
    void deleteOnlyOwnItemAndIsIdempotent() throws Exception {
        jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (7901,10101,2),(7902,10101,7)");
        for (int i=0; i<2; i++) {
            mvc.perform(delete("/api/cart/items/10101").param("userId", "7902").with(buyer(7901)).with(csrf()))
                    .andExpect(status().isNoContent());
        }
        assertEquals(1, count());
        assertEquals(7, quantity(7902, 10101));
        mvc.perform(delete("/api/cart/items/10999").with(buyer(7901)).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings={"{}", "{\"quantity\":null}", "{\"quantity\":0}", "{\"quantity\":-1}",
            "{\"quantity\":1.5}", "{\"quantity\":2147483648}", "{\"quantity\":\"no\"}", "{bad"})
    void invalidQuantityCannotCreateOrChangeCart(String body) throws Exception {
        mvc.perform(put("/api/cart/items/10101").with(buyer(7901)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(strings={"0", "-1", "no", "1.5", "9223372036854775808"})
    void invalidProductIdIsRejectedForBothWrites(String id) throws Exception {
        mvc.perform(put("/api/cart/items/"+id).with(buyer(7901)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/cart/items/"+id).with(buyer(7901)).with(csrf()))
                .andExpect(status().isBadRequest());
        assertEquals(0, count());
    }

    @Test
    void missingAndOffSaleProductCannotBeSet() throws Exception {
        mvc.perform(put("/api/cart/items/10999").with(buyer(7901)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/cart/items/10102").with(buyer(7901)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isConflict());
        assertEquals(0, count());
    }

    @Test
    void intentionMayExceedStockAndAcceptLargestInteger() throws Exception {
        mvc.perform(put("/api/cart/items/10101").with(buyer(7901)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":100}"))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/cart/items/10103").with(buyer(7901)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2147483647}"))
                .andExpect(status().isNoContent());
        assertEquals(100, quantity(7901, 10101));
        assertEquals(Integer.MAX_VALUE, quantity(7901, 10103));
        assertEquals(5, jdbc.queryForObject("SELECT stock FROM product WHERE id=10101", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT stock FROM product WHERE id=10103", Integer.class));
    }

    @Test
    void listReadsCurrentProductValuesInOrderAndRetainsOffSaleItems() throws Exception {
        jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (7901,10103,4),(7901,10101,2)");
        jdbc.update("UPDATE product SET name='当前新名称',price=150.50,stock=3,status='OFF_SALE' WHERE id=10101");
        mvc.perform(get("/api/cart/items").with(buyer(7901))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].productId").value(10101))
                .andExpect(jsonPath("$[0].name").value("当前新名称"))
                .andExpect(jsonPath("$[0].price").value(150.50))
                .andExpect(jsonPath("$[0].stock").value(3))
                .andExpect(jsonPath("$[0].status").value("OFF_SALE"))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[1].productId").value(10103));
        mvc.perform(put("/api/cart/items/10101").with(buyer(7901)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isConflict());
        assertEquals(2, quantity(7901, 10101));
        mvc.perform(delete("/api/cart/items/10101").with(buyer(7901)).with(csrf()))
                .andExpect(status().isNoContent());
        assertEquals(1, count());
    }

    @Test
    void anonymousCannotReadOrWriteCart() throws Exception {
        mvc.perform(get("/api/cart/items")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/cart/items/10101").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/cart/items/10101").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void bothWritesRequireValidCsrf() throws Exception {
        mvc.perform(put("/api/cart/items/10101").with(buyer(7901)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/cart/items/10101").with(buyer(7901))).andExpect(status().isForbidden());
        mvc.perform(put("/api/cart/items/10101").with(buyer(7901)).with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/cart/items/10101").with(buyer(7901)).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        assertEquals(0, count());
    }

    @Test
    void adminAlsoOperatesOnlyOwnCart() throws Exception {
        jdbc.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (7902,10101,7)");
        var admin=user(new PlatformUserDetails(new AppUser(7901L,"cart_admin","unused","ADMIN")));
        mvc.perform(put("/api/cart/items/10101").with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/cart/items").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].quantity").value(2));
        assertEquals(7, quantity(7902, 10101));
    }

    private MockHttpServletRequestBuilder add(long productId, int delta) {
        return post("/api/cart/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + productId + ",\"quantity\":" + delta + "}");
    }

    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM cart_item", Integer.class); }
    private int quantity(long owner,long product) {
        return jdbc.queryForObject("SELECT quantity FROM cart_item WHERE user_id=? AND product_id=?",Integer.class,owner,product);
    }
    private RequestPostProcessor buyer(long id) {
        return user(new PlatformUserDetails(new AppUser(id,"cart_api_buyer_"+id,"unused","USER")));
    }
}
