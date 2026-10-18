package com.elaina.limitedtrade.address;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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
class AddressTransactionIntegrationTest {
    @Autowired AddressService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    private boolean owned;
    private final List<MockHttpSession> sessions=new ArrayList<>();
    private final SaveAddressRequest request=new SaveAddressRequest("张三","13800138000","浙江","杭州","西湖","学院路1号");
    @BeforeEach void prepare() {
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id IN (7951,7952) OR username IN ('address_persist_1','address_persist_2')",Integer.class));
        String hash=passwords.encode("AddressPersistence2026!");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (7951,'address_persist_1',?,'USER'),(7952,'address_persist_2',?,'USER')",hash,hash);
        owned=true;
    }
    @AfterEach void cleanup() {
        for(var session:sessions)if(!session.isInvalid())session.invalidate();
        if(owned) {
            jdbc.update("DELETE FROM user_address WHERE user_id IN (7951,7952)");
            jdbc.update("DELETE FROM app_user WHERE id IN (7951,7952)");
        }
    }
    @Test void rollbackRestoresContentAndDefaults() {
        long a=service.createAddress(7951,request).id(),b=service.createAddress(7951,request).id();
        var original=service.listAddresses(7951);
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            service.createAddress(7951,request);
            service.updateAddress(7951,a,new SaveAddressRequest("李四","13900139000","江苏","南京","鼓楼","测试路"));
            service.setDefaultAddress(7951,b);
            service.deleteAddress(7951,a);
            tx.setRollbackOnly();
        });
        assertEquals(original,service.listAddresses(7951));
        new TransactionTemplate(transactions).executeWithoutResult(tx->{service.deleteAddress(7951,b);tx.setRollbackOnly();});
        assertEquals(original,service.listAddresses(7951));
    }
    @Test void realSqlFailureAfterClearingDefaultRestoresPreviousDefault() {
        long a=service.createAddress(7951,request).id(),b=service.createAddress(7951,request).id();
        var original=service.listAddresses(7951);var other=service.createAddress(7952,request);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='test_address_default_reject_7951'",Integer.class));
        jdbc.execute("CREATE TRIGGER test_address_default_reject_7951 BEFORE UPDATE ON user_address FOR EACH ROW BEGIN IF NEW.user_id=7951 AND OLD.is_default=0 AND NEW.is_default=1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='address controlled failure'; END IF; END");
        try {
            assertThrows(DataAccessException.class,()->service.setDefaultAddress(7951,b));
            assertEquals(original,service.listAddresses(7951));
            assertEquals(a,service.listAddresses(7951).getFirst().id());
            assertEquals(List.of(other),service.listAddresses(7952));
        } finally {jdbc.execute("DROP TRIGGER test_address_default_reject_7951");}
    }
    @Test void logoutAndNewLoginKeepCommittedAddresses() throws Exception {
        var first=login("address_persist_1");
        mvc.perform(post("/api/addresses").session(first).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientName\":\"张三\",\"phone\":\"13800138000\",\"province\":\"浙江\",\"city\":\"杭州\",\"district\":\"西湖\",\"detailAddress\":\"学院路1号\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/logout").session(first).with(csrf())).andExpect(status().isNoContent());
        var second=login("address_persist_1");
        mvc.perform(get("/api/addresses").session(second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].recipientName").value("张三"));
        var other=login("address_persist_2");
        mvc.perform(get("/api/addresses").session(other)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(post("/api/auth/logout").session(second).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout").session(other).with(csrf())).andExpect(status().isNoContent());
    }
    @Test void addressWritesDoNotChangeExistingBusiness() {
        var tables=List.of("product","cart_item","trade_order","activity","order_timeout_outbox","product_category");
        var before=tables.stream().map(t->jdbc.queryForList("SELECT * FROM "+t)).toList();
        long a=service.createAddress(7951,request).id(),b=service.createAddress(7951,request).id();
        service.updateAddress(7951,b,request);service.setDefaultAddress(7951,b);service.deleteAddress(7951,a);
        var after=tables.stream().map(t->jdbc.queryForList("SELECT * FROM "+t)).toList();
        assertEquals(before,after);
    }
    private MockHttpSession login(String username) throws Exception {
        var result=mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password","AddressPersistence2026!"))
                .andExpect(status().isNoContent()).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);
        assertNotNull(session);sessions.add(session);return session;
    }
}
