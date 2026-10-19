package com.elaina.limitedtrade.auth;

import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class RegistrationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    final String prefixPattern="registration!_it20!_%";
    final String password="RegisterPersist2026!";
    final List<MockHttpSession> sessions=new ArrayList<>();
    boolean owned,productOwned;

    @BeforeEach void prepare(){
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username LIKE ? ESCAPE '!'",Integer.class,prefixPattern));
        owned=true;
    }
    @AfterEach void cleanup(){
        for(var session:sessions)if(!session.isInvalid())session.invalidate();
        if(owned){
            jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN(SELECT id FROM trade_order WHERE user_id IN(SELECT id FROM app_user WHERE username LIKE ? ESCAPE '!'))",prefixPattern);
            jdbc.update("DELETE FROM trade_order WHERE user_id IN(SELECT id FROM app_user WHERE username LIKE ? ESCAPE '!')",prefixPattern);
            jdbc.update("DELETE FROM app_user WHERE username LIKE ? ESCAPE '!'",prefixPattern);
        }
        if(productOwned)jdbc.update("DELETE FROM product WHERE id=19001");
    }
    String body(String name,String candidate)throws Exception{return json.writeValueAsString(Map.of("username",name,"password",candidate));}
    JsonNode register(String name)throws Exception{
        var r=mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(name,password)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }
    MockHttpSession login(String name)throws Exception{
        var r=mvc.perform(post("/api/auth/login").with(csrf()).param("username",name).param("password",password)).andExpect(status().isNoContent()).andReturn();
        var session=(MockHttpSession)r.getRequest().getSession(false);assertNotNull(session);sessions.add(session);return session;
    }

    @Test void realCsrfRegistrationPersistsAndExistingLoginCanPurchaseAsOrdinaryUser()throws Exception{
        String name="registration_it20_workflow";
        var csrfResult=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)csrfResult.getRequest().getSession(false);sessions.add(session);
        var token=json.readTree(csrfResult.getResponse().getContentAsString());
        var registration=mvc.perform(post("/api/auth/register").session(session).header(token.path("headerName").asText(),token.path("token").asText())
                .contentType(MediaType.APPLICATION_JSON).content(body(name,password))).andExpect(status().isCreated()).andReturn();
        long id=json.readTree(registration.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").session(session).header(token.path("headerName").asText(),token.path("token").asText())
                .param("username",name).param("password",password)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.role").value("USER"));
        mvc.perform(get("/api/admin/orders").session(session)).andExpect(status().isForbidden());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=19001",Integer.class));productOwned=true;
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES(19001,'注册购买测试商品',20,2,'ON_SALE')");
        mvc.perform(post("/api/orders").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":19001,\"quantity\":1,\"requestId\":\"registration-flow\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.totalAmount").value(20));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        var second=login(name);
        mvc.perform(get("/api/orders").session(second)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/auth/login").with(csrf()).param("username",name).param("password","WrongPassword2026!"))
                .andExpect(status().isUnauthorized());
    }
    @Test void concurrentSameUsernameCreatesExactlyOneAccount()throws Exception{
        String name="registration_it20_concurrent";var pool=Executors.newFixedThreadPool(8);var gate=new CountDownLatch(1);
        try{
            var futures=new ArrayList<Future<Integer>>();
            for(int i=0;i<8;i++){final int index=i;futures.add(pool.submit(()->{gate.await();return mvc.perform(post("/api/auth/register").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body(name,"ConcurrentPassword20!"+index))).andReturn().getResponse().getStatus();}));}
            gate.countDown();var statuses=new ArrayList<Integer>();for(var f:futures)statuses.add(f.get(20,TimeUnit.SECONDS));
            assertEquals(1,statuses.stream().filter(code->code==201).count());assertEquals(7,statuses.stream().filter(code->code==409).count());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?",Integer.class,name));
        var row=jdbc.queryForMap("SELECT password_hash,role FROM app_user WHERE username=?",name);assertEquals("USER",row.get("role"));
        int matches=0;for(int i=0;i<8;i++)if(passwords.matches("ConcurrentPassword20!"+i,(String)row.get("password_hash")))matches++;
        assertEquals(1,matches);
    }
    @Test void existingAccountDuplicateCannotChangePasswordAndRemainsLoginable()throws Exception{
        String name="registration_it20_duplicate";long id=register(name).path("id").asLong();
        var before=jdbc.queryForMap("SELECT * FROM app_user WHERE id=?",id);
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(name,"AttemptReplacement2026!")))
                .andExpect(status().isConflict());
        assertEquals(before,jdbc.queryForMap("SELECT * FROM app_user WHERE id=?",id));
        mvc.perform(get("/api/auth/me").session(login(name))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }
    @Test void nonDuplicateDatabaseFailurePropagatesAndCanRetryAfterRecovery()throws Exception{
        String name="registration_it20_failure",trigger="test_registration_reject20";
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name=?",Integer.class,trigger));
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON app_user FOR EACH ROW BEGIN IF NEW.username='"+name+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled registration failure'; END IF; END");
        try{
            var failure=assertThrows(jakarta.servlet.ServletException.class,()->register(name));
            assertInstanceOf(DataAccessException.class,failure.getCause());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?",Integer.class,name));
        }finally{jdbc.execute("DROP TRIGGER "+trigger);}
        assertTrue(register(name).path("id").asLong()>0);
    }
}
