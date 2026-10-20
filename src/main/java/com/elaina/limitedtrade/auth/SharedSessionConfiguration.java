package com.elaina.limitedtrade.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
@Profile("shared-session")
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800, redisNamespace = "${spring.session.redis.namespace}")
public class SharedSessionConfiguration {
    @Bean
    public CookieSerializer cookieSerializer() {
        var serializer = new DefaultCookieSerializer();
        // 与既有Security退出配置使用相同Cookie名，所有实例共用路径。
        serializer.setCookieName("JSESSIONID");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseBase64Encoding(false);
        return serializer;
    }
}
