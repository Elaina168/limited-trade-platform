package com.elaina.limitedtrade.cart;

import java.util.ArrayList;
import java.util.List;
import com.elaina.limitedtrade.order.CreateOrderRequest;
import com.elaina.limitedtrade.order.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CartPersistenceIntegrationTest {
    @Autowired private CartService carts;
    @Autowired private OrderService orders;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MockMvc mvc;
    @Autowired private PasswordEncoder passwords;
    private boolean owned;
    private final List<MockHttpSession> sessions=new ArrayList<>();

    @BeforeEach
    void prepareCommittedFixtures() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id IN (7921,7922)",Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=10121",Integer.class));
        owned=true;
        String hash=passwords.encode("CartPersistence2026!");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (7921,'cart_persist_1',?,'USER'),(7922,'cart_persist_2',?,'USER')",hash,hash);
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (10121,'持久化购物车商品',20,10,'ON_SALE')");
    }

    @AfterEach
    void cleanupOwnedFixtures() {
        for(var session:sessions) if(!session.isInvalid()) session.invalidate();
        if(owned) {
            jdbc.update("DELETE FROM cart_item WHERE user_id IN (7921,7922)");
            jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN (SELECT id FROM trade_order WHERE user_id IN (7921,7922))");
            jdbc.update("DELETE FROM trade_order WHERE user_id IN (7921,7922)");
            jdbc.update("DELETE FROM product WHERE id=10121");
            jdbc.update("DELETE FROM app_user WHERE id IN (7921,7922)");
        }
    }

    @Test
    void outerRollbackLeavesCartUnchanged() {
        carts.setQuantity(7921,10121,2);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            carts.setQuantity(7921,10121,5);
            tx.setRollbackOnly();
        });
        assertEquals(2,quantity());
        carts.removeItem(7921,10121);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            carts.setQuantity(7921,10121,3);
            tx.setRollbackOnly();
        });
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cart_item WHERE user_id=7921",Integer.class));
    }

    @Test
    void logoutAndFreshLoginKeepCommittedCart() throws Exception {
        var first=login("cart_persist_1");
        mvc.perform(put("/api/cart/items/10121").session(first).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout").session(first).with(csrf())).andExpect(status().isNoContent());
        assertEquals(3,quantity());
        var second=login("cart_persist_1");
        mvc.perform(get("/api/cart/items").session(second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(10121))
                .andExpect(jsonPath("$[0].quantity").value(3));
        var other=login("cart_persist_2");
        mvc.perform(get("/api/cart/items").session(other)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(post("/api/auth/logout").session(second).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout").session(other).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void cartChangesDoNotCreateOrdersAndDirectOrderDoesNotClearCart() {
        carts.setQuantity(7921,10121,3);
        assertEquals(10,jdbc.queryForObject("SELECT stock FROM product WHERE id=10121",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE user_id=7921",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM order_timeout_outbox",Integer.class));
        var result=orders.createOrder(7921,new CreateOrderRequest(10121L,2,"cart-direct-order",null));
        assertTrue(result.created());
        assertEquals(8,jdbc.queryForObject("SELECT stock FROM product WHERE id=10121",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_timeout_outbox WHERE order_id=?",Integer.class,result.order().getId()));
        assertEquals(3,quantity());
    }

    private int quantity() {
        return jdbc.queryForObject("SELECT quantity FROM cart_item WHERE user_id=7921 AND product_id=10121",Integer.class);
    }

    private MockHttpSession login(String username) throws Exception {
        var result=mvc.perform(post("/api/auth/login").with(csrf()).param("username",username)
                        .param("password","CartPersistence2026!"))
                .andExpect(status().isNoContent()).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);
        assertNotNull(session);
        sessions.add(session);
        return session;
    }
}
