package com.elaina.limitedtrade.auth;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class RegistrationApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    final String password="RegisterStage2026!";

    String body(String username,String password)throws Exception{return json.writeValueAsString(Map.of("username",username,"password",password));}
    void register(String content,int code)throws Exception{
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().is(code));
    }
    int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM app_user",Integer.class);}

    @Test void anonymousRegistrationCreatesUserWithoutExposingPasswordOrLoggingIn()throws Exception{
        var r=mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("registration_api20",password)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value("registration_api20")).andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn();
        var response=json.readTree(r.getResponse().getContentAsString());assertEquals(3,response.size());
        var row=jdbc.queryForMap("SELECT id,password_hash,role FROM app_user WHERE username='registration_api20'");
        assertEquals(response.path("id").asLong(),((Number)row.get("id")).longValue());
        String hash=(String)row.get("password_hash");assertNotEquals(password,hash);assertTrue(passwords.matches(password,hash));
        mvc.perform(get("/api/auth/me").session((MockHttpSession)r.getRequest().getSession())).andExpect(status().isUnauthorized());
    }
    @Test void suppliedRoleIdAndHashCannotOverrideServerValues()throws Exception{
        var b=new HashMap<String,Object>(Map.of("username","registration_privilege20","password",password,"role","ADMIN","id",999999,"passwordHash","not-the-real-hash"));
        register(json.writeValueAsString(b),201);
        assertEquals("USER",jdbc.queryForObject("SELECT role FROM app_user WHERE username='registration_privilege20'",String.class));
        assertNotEquals(999999L,jdbc.queryForObject("SELECT id FROM app_user WHERE username='registration_privilege20'",Long.class));
        String hash=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username='registration_privilege20'",String.class);
        assertTrue(passwords.matches(password,hash));
    }
    @Test void duplicateUsernameConflictsWithoutReplacingPasswordOrRole()throws Exception{
        register(body("registration_duplicate20",password),201);
        var before=jdbc.queryForMap("SELECT * FROM app_user WHERE username='registration_duplicate20'");
        register(body("registration_duplicate20","OtherPassword2026!"),409);
        assertEquals(before,jdbc.queryForMap("SELECT * FROM app_user WHERE username='registration_duplicate20'"));
    }
    @ParameterizedTest @ValueSource(strings={"", "ab", " registration20", "registration20 ", "register-name", "用户注册测试", "registration.20"})
    void invalidUsernameIsRejectedWithoutCreatingAccounts(String username)throws Exception{
        int before=count();register(body(username,password),400);assertEquals(before,count());
    }
    @Test void usernameLengthBoundaries()throws Exception{
        register(body("r20",password),201);
        register(body("r".repeat(50),password),201);
        int before=count();register(body("r".repeat(51),password),400);assertEquals(before,count());
    }
    @ParameterizedTest @ValueSource(strings={"", "1234567", "        "})
    void shortOrBlankPasswordRejected(String candidate)throws Exception{
        int before=count();register(body("registration_password20",candidate),400);assertEquals(before,count());
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"username\":\"registration_missing20\"}","{\"password\":\"RegisterStage2026!\"}","{\"username\":null,\"password\":null}"})
    void missingFieldsRejected(String content)throws Exception{
        int before=count();register(content,400);assertEquals(before,count());
    }
    @Test void bcryptUtf8ByteLimitIsValidatedBeforeEncoding()throws Exception{
        int before=count();register(body("registration_overlong20","a".repeat(73)),400);
        register(body("registration_overbytes20","密".repeat(25)),400);assertEquals(before,count());
        String candidate="密".repeat(24);register(body("registration_unicode20",candidate),201);
        assertTrue(passwords.matches(candidate,jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username='registration_unicode20'",String.class)));
        register(body("registration_ascii72_20","a".repeat(72)),201);
        register(body("registration_ascii8_20","a".repeat(8)),201);
    }
    @Test void passwordWhitespaceIsPreservedAndEqualPasswordsHaveDifferentSalt()throws Exception{
        String candidate="  Register2026!  ";register(body("registration_space20",candidate),201);
        String first=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username='registration_space20'",String.class);
        assertTrue(passwords.matches(candidate,first));assertFalse(passwords.matches(candidate.strip(),first));
        register(body("registration_salt20",candidate),201);
        String second=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username='registration_salt20'",String.class);
        assertNotEquals(first,second);assertTrue(passwords.matches(candidate,second));
    }
    @Test void csrfAndJsonBoundariesAreEnforced()throws Exception{
        int before=count();String content=body("registration_csrf20",password);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/register").with(csrf().useInvalidToken()).contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{")) .andExpect(status().isBadRequest());
        assertEquals(before,count());
    }
}
