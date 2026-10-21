package com.elaina.limitedtrade.system;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PLAYWRIGHT_MODULE", matches = ".+")
class OpenApiBrowserIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    @Test void browserExecutesRealSessionCsrfAndRoleWorkflow() throws Exception {
        assertEquals("limited_trade_platform_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String user = "docs_user_" + suffix, admin = "docs_admin_" + suffix;
        String product = "文档调试商品_" + suffix;
        String password = "DocsBrowser2026!";
        Path output = Path.of("tools", "openapi-browser.log");
        try {
            // 仅创建独立测试账号和商品，浏览器通过真实HTTP访问随机本机端口。
            jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,?,'USER'),(?,?,'ADMIN')",
                    user, passwords.encode(password), admin, passwords.encode(password));
            jdbc.update("INSERT INTO product(name,price,stock,status) VALUES (?,19.90,5,'ON_SALE')", product);
            long productId = jdbc.queryForObject("SELECT id FROM product WHERE name=?", Long.class, product);
            var command = new ProcessBuilder("node", "src/test/frontend/openapi.cjs")
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            command.environment().put("DOCS_BASE_URL", "http://127.0.0.1:" + port);
            command.environment().put("DOCS_USERNAME", user);
            command.environment().put("DOCS_ADMIN", admin);
            command.environment().put("DOCS_PASSWORD", password);
            command.environment().put("DOCS_PRODUCT_ID", Long.toString(productId));
            Process process = command.start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
                fail("浏览器验证超时，查看" + output);
            }
            assertEquals(0, process.exitValue(), "浏览器验证失败，查看" + output);
            assertEquals("OFF_SALE", jdbc.queryForObject("SELECT status FROM product WHERE id=?", String.class, productId));
        } finally {
            jdbc.update("DELETE FROM cart_item WHERE user_id IN (SELECT id FROM app_user WHERE username IN (?,?))", user, admin);
            jdbc.update("DELETE FROM product WHERE name=?", product);
            jdbc.update("DELETE FROM app_user WHERE username IN (?,?)", user, admin);
        }
    }
}
