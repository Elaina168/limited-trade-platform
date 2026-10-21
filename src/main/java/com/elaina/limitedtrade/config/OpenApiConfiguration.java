package com.elaina.limitedtrade.config;

import java.util.List;
import java.util.Set;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    private static final Set<String> PUBLIC_READS = Set.of(
            "/api/ping", "/api/products", "/api/products/search", "/api/categories", "/api/auth/csrf");
    private static final Set<String> CREATED = Set.of(
            "/api/addresses", "/api/admin/products", "/api/admin/categories", "/api/admin/activities");
    private static final Set<String> EMPTY_SUCCESS = Set.of(
            "PUT /api/cart/items/{productId}", "DELETE /api/cart/items/{productId}",
            "DELETE /api/addresses/{id}", "PUT /api/addresses/{id}/default");
    private static final Set<String> NOT_FOUND = Set.of(
            "GET /api/activities/{id}", "GET /api/activities/{activityId}/coupons",
            "POST /api/admin/products", "PUT /api/admin/products/{id}", "PATCH /api/admin/products/{id}/status",
            "PUT /api/admin/categories/{id}", "POST /api/admin/activities", "POST /api/admin/coupons",
            "PUT /api/cart/items/{productId}", "POST /api/coupons/{id}/claim",
            "PUT /api/addresses/{id}", "DELETE /api/addresses/{id}", "PUT /api/addresses/{id}/default",
            "POST /api/orders", "POST /api/orders/checkout", "GET /api/orders/{id}",
            "POST /api/orders/{id}/pay", "POST /api/orders/{id}/cancel", "POST /api/orders/{id}/receive",
            "GET /api/admin/orders/{id}", "POST /api/admin/orders/{id}/ship");
    private static final Set<String> CONFLICT = Set.of(
            "POST /api/auth/register", "POST /api/admin/categories", "PUT /api/admin/categories/{id}",
            "PUT /api/cart/items/{productId}", "POST /api/coupons/{id}/claim",
            "POST /api/orders", "POST /api/orders/checkout", "POST /api/orders/{id}/pay",
            "POST /api/orders/{id}/cancel", "POST /api/orders/{id}/receive", "POST /api/admin/orders/{id}/ship");

    @Bean
    OpenAPI platformOpenApi() {
        return new OpenAPI().info(new Info().title("限量交易平台 API").version("1.0")
                .description("""
                        在线调试入口：/api-docs.html。先展开“认证 / 登录”，使用现有账号执行表单登录，
                        再调用本人或管理接口；浏览器自动携带Session Cookie，不需要填写JWT或手工复制Cookie。
                        此页面与商城共用当前浏览器会话，登录和退出会同时影响商城。

                        此入口每次写请求会自动调用GET /api/auth/csrf，并把Token写入返回的headerName请求头。
                        其他客户端须自行获取Token并保留Cookie，登录或退出后重新获取Token。
                        所有管理接口要求ADMIN；本人接口始终按登录身份限制数据范围。

                        错误以HTTP状态及X-Trace-Id响应头为准；Security错误可能没有正文，
                        MVC错误沿用Spring Boot默认处理，不承诺统一error/message字段。
                        所有/api/响应含X-Trace-Id；下单429额外含Retry-After（秒）。
                        调试会执行真实业务操作；支付为模拟支付，物流由管理员登记。
                        """))
                .components(new Components()
                        .addSecuritySchemes("SessionAuth", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE).name("JSESSIONID")
                                .description("通过表单登录建立会话，由浏览器自动携带Cookie，无需在Authorize中手填。"))
                        .addSecuritySchemes("CsrfToken", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-CSRF-TOKEN")
                                .description("从/api/auth/csrf获取；在线调试入口自动处理，登录和退出后需使用新Token。")));
    }

    @Bean
    OperationCustomizer stableOperationIds() {
        return (operation, handler) -> operation.operationId(
                handler.getBeanType().getSimpleName() + "_" + handler.getMethod().getName());
    }

    @Bean
    OpenApiCustomizer platformContracts() {
        return api -> {
            // 登录与退出由Security过滤器处理，显式补文档，不创建重复的业务Controller。
            var credentials = new ObjectSchema()
                    .addProperty("username", new StringSchema().description("现有账号用户名"))
                    .addProperty("password", new StringSchema().format("password").writeOnly(true))
                    .required(List.of("username", "password"));
            var login = new Operation().operationId("login").addTagsItem("认证").summary("登录")
                    .description("表单登录，成功204并建立Session；凭据错误401。登录后重新获取CSRF Token。")
                    .requestBody(new RequestBody().required(true).content(new Content()
                            .addMediaType("application/x-www-form-urlencoded", new MediaType().schema(credentials))))
                    .responses(new ApiResponses().addApiResponse("204", new ApiResponse().description("登录成功，无正文"))
                            .addApiResponse("401", new ApiResponse().description("用户名或密码错误，无正文")));
            api.path("/api/auth/login", new PathItem().post(login));
            api.path("/api/auth/logout", new PathItem().post(new Operation().operationId("logout")
                    .addTagsItem("认证").summary("退出登录").description("使当前Session失效，成功204，无正文。")
                    .responses(new ApiResponses().addApiResponse("204", new ApiResponse().description("退出成功，无正文")))));

            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                boolean read = method == PathItem.HttpMethod.GET;
                boolean filterAuth = path.equals("/api/auth/login") || path.equals("/api/auth/logout");
                boolean anonymous = read && (PUBLIC_READS.contains(path) || path.startsWith("/api/activities"))
                        || path.equals("/api/auth/register") || filterAuth;
                boolean admin = path.startsWith("/api/admin/");
                String key = method.name() + " " + path;
                var security = new SecurityRequirement();
                if (!anonymous) security.addList("SessionAuth");
                if (!read) security.addList("CsrfToken");
                operation.setSecurity(security.isEmpty() ? List.of() : List.of(security));
                operation.setDescription(operation.getDescription() + "\n\n权限："
                        + (admin ? "ADMIN" : anonymous ? "允许匿名" : "须登录，仅按当前身份访问")
                        + (read ? "。" : "；须有效CSRF Token。"));

                ApiResponses responses = operation.getResponses();
                ApiResponse success = responses.get("200");
                if (method == PathItem.HttpMethod.POST && CREATED.contains(path)) {
                    responses.remove("200");
                    responses.addApiResponse("201", new ApiResponse().description("创建成功")
                            .content(success.getContent()));
                } else if (EMPTY_SUCCESS.contains(key)) {
                    responses.remove("200");
                    responses.addApiResponse("204", new ApiResponse().description("操作成功，无正文"));
                }
                if (!anonymous) responses.addApiResponse("401", new ApiResponse().description("未登录或会话已失效"));
                if (!read || admin) responses.addApiResponse("403", new ApiResponse()
                        .description(admin && read ? "当前用户不具备ADMIN权限" : "CSRF Token无效或当前身份权限不足"));
                if (!filterAuth && (!read || operation.getParameters() != null && !operation.getParameters().isEmpty()))
                    responses.addApiResponse("400", new ApiResponse().description("路径、查询参数或请求正文校验失败"));
                if (NOT_FOUND.contains(key)) responses.addApiResponse("404", new ApiResponse()
                        .description("目标记录不存在；本人资源接口对非本人记录也返回404"));
                if (CONFLICT.contains(key)) responses.addApiResponse("409", new ApiResponse()
                        .description("业务状态、购买参数、库存额度或唯一性冲突，具体条件见接口说明"));
                if (path.equals("/api/admin/operations/order-messages"))
                    responses.addApiResponse("503", new ApiResponse().description("监控数据库不可用，未返回伪造的正常快照"));
                if (method == PathItem.HttpMethod.POST && (path.equals("/api/orders") || path.equals("/api/orders/checkout"))) {
                    responses.get("200").setDescription("相同请求的幂等重试，返回原订单");
                    responses.addApiResponse("201", new ApiResponse().description("首次创建订单")
                            .content(responses.get("200").getContent()));
                    responses.addApiResponse("429", new ApiResponse().description("用户下单尝试超过共享额度")
                            .addHeaderObject("Retry-After", new Header().description("等待秒数")
                                    .schema(new IntegerSchema().format("int64").minimum(java.math.BigDecimal.ONE))));
                    responses.addApiResponse("503", new ApiResponse().description("Redis下单限流服务暂不可用"));
                }
                responses.values().forEach(response -> response.addHeaderObject("X-Trace-Id", new Header()
                        .description("本次HTTP请求的关联编号，与下单requestId不同")
                        .schema(new StringSchema().pattern("[a-f0-9]{32}"))));
            }));
        };
    }
}
