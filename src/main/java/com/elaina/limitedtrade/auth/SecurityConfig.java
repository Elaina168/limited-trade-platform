package com.elaina.limitedtrade.auth;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 保留默认 CSRF 防护和 Session 固定攻击防护，不自行实现密码比较。
        http
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/*.js", "/assets/styles.css").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api-docs.html", "/swagger-ui.html",
                                "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/ping", "/api/products", "/api/products/search",
                                "/api/categories", "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/activities/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        .requestMatchers("/api/orders", "/api/orders/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/agent/chat").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/agent/conversations",
                                "/api/agent/conversations/*/messages").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/agent/conversations/*/messages").authenticated()
                        .requestMatchers("/api/cart/items", "/api/cart/items/**").authenticated()
                        .requestMatchers("/api/addresses", "/api/addresses/**").authenticated()
                        .requestMatchers("/api/coupons/**").authenticated()
                .anyRequest().denyAll())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .failureHandler((request, response, exception) ->
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED))
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
