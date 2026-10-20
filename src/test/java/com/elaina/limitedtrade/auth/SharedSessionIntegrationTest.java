package com.elaina.limitedtrade.auth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.elaina.limitedtrade.ratelimit.OrderRateLimitExceededException;
import com.elaina.limitedtrade.ratelimit.OrderRateLimitProperties;
import com.elaina.limitedtrade.ratelimit.RedisOrderRateLimiter;
import com.elaina.limitedtrade.user.AppUser;
import com.elaina.limitedtrade.user.AppUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SharedSessionIntegrationTest {
    final String prefix = "limited-trade:test:shared-session:" + UUID.randomUUID();
    final ObjectMapper json = new ObjectMapper();
    AnnotationConfigWebApplicationContext first;
    AnnotationConfigWebApplicationContext second;
    MockMvc a;
    MockMvc b;

    @BeforeEach void startTwoContexts() throws Exception {
        first = context(); second = context();
        a = mvc(first); b = mvc(second);
    }

    AnnotationConfigWebApplicationContext context() {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("shared-session");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("session-test",
                Map.of("spring.session.redis.namespace", prefix + ":sessions")));
        context.register(WebConfiguration.class, SharedSessionConfiguration.class);
        context.refresh();
        return context;
    }

    MockMvc mvc(AnnotationConfigWebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSessionRepositoryFilter", Filter.class))
                .apply(springSecurity()).build();
    }

    @AfterEach void cleanup() {
        try {
            if (first != null) {
                var redis = first.getBean(StringRedisTemplate.class);
                var keys = new ArrayList<String>();
                try (var cursor = redis.scan(ScanOptions.scanOptions().match(prefix + ":*").count(100).build())) {
                    cursor.forEachRemaining(keys::add);
                }
                if (!keys.isEmpty()) redis.delete(keys);
            }
        } finally {
            if (second != null) second.close();
            if (first != null) first.close();
        }
    }

    @Test void loginRotatesSessionAndIdentityCsrfAndLogoutWorkAcrossContexts() throws Exception {
        var before = a.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie old = before.getResponse().getCookie("JSESSIONID");
        assertNotNull(old);
        String token = json.readTree(before.getResponse().getContentAsString()).path("token").asText();
        var login = b.perform(post("/api/auth/login").cookie(old)
                        .header("X-CSRF-TOKEN", token).param("username", "session_user").param("password", "SessionTest2026!"))
                .andExpect(status().isNoContent()).andReturn();
        Cookie current = login.getResponse().getCookie("JSESSIONID");
        assertNotNull(current);
        assertNotEquals(old.getValue(), current.getValue());
        assertEquals("/", current.getPath());
        assertTrue(current.isHttpOnly());
        a.perform(get("/api/auth/me").cookie(old)).andExpect(status().isUnauthorized());
        for (var instance : List.of(a, b)) {
            instance.perform(get("/api/auth/me").cookie(current)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(42001)).andExpect(jsonPath("$.role").value("USER"));
            instance.perform(get("/api/admin/products").cookie(current)).andExpect(status().isForbidden());
        }
        a.perform(post("/api/auth/logout").cookie(current).header("X-CSRF-TOKEN", token))
                .andExpect(status().isForbidden());
        var after = b.perform(get("/api/auth/csrf").cookie(current)).andExpect(status().isOk()).andReturn();
        String fresh = json.readTree(after.getResponse().getContentAsString()).path("token").asText();
        a.perform(post("/api/auth/logout").cookie(current).header("X-CSRF-TOKEN", fresh))
                .andExpect(status().isNoContent());
        b.perform(get("/api/auth/me").cookie(current)).andExpect(status().isUnauthorized());
    }

    @Test void sessionsHaveRedisExpiryAndBothContextsConsumeOneRateLimitBudget() throws Exception {
        a.perform(get("/api/auth/csrf")).andExpect(status().isOk());
        var redis = first.getBean(StringRedisTemplate.class);
        var keys = new ArrayList<String>();
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(prefix + ":sessions:*").count(100).build())) {
            cursor.forEachRemaining(keys::add);
        }
        assertEquals(1, keys.size());
        long ttl = redis.getExpire(keys.getFirst());
        assertTrue(ttl > 0 && ttl <= 1800);
        var properties = new OrderRateLimitProperties(true, 1, 30, prefix + ":rate");
        new RedisOrderRateLimiter(redis, properties).check(42001);
        var denied = assertThrows(OrderRateLimitExceededException.class, () ->
                new RedisOrderRateLimiter(second.getBean(StringRedisTemplate.class), properties).check(42001));
        assertEquals(429, denied.getStatusCode().value());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, AuthController.class, DatabaseUserDetailsService.class})
    static class WebConfiguration {
        @Bean RedisConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 6379),
                    LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build());
        }
        @Bean StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) { return new StringRedisTemplate(factory); }
        @Bean AppUserMapper appUserMapper(PasswordEncoder passwords) {
            var mapper = mock(AppUserMapper.class);
            when(mapper.findByUsername("session_user")).thenReturn(new AppUser(42001L,"session_user",passwords.encode("SessionTest2026!"),"USER"));
            return mapper;
        }
        @Bean RegistrationService registrationService() { return mock(RegistrationService.class); }
    }
}
