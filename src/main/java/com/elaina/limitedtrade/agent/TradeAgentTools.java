package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.order.OrderResponse;
import com.elaina.limitedtrade.order.OrderService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class TradeAgentTools {
    private static final Set<String> STATUSES = Set.of("PENDING_PAYMENT", "PAID", "CANCELED", "CLOSED", "SHIPPED", "COMPLETED");
    private static final String SERVICE_SOURCE = "src/main/java/com/elaina/limitedtrade/order/OrderService.java";
    private final OrderService orders;

    public TradeAgentTools(OrderService orders) { this.orders = orders; }

    @Tool(name = "list_my_orders", description = "分页查询当前登录用户本人的订单，最近订单按编号倒序；状态可选。查询时间及expiresAt是事实，不保证查询后仍可支付。", resultConverter = JsonResultConverter.class)
    public OrderPage listMyOrders(@ToolParam(description = "页码，从1开始") int page,
                                 @ToolParam(description = "每页条数，1到10") int size,
                                 @ToolParam(description = "可选状态：PENDING_PAYMENT、PAID、CANCELED、CLOSED、SHIPPED、COMPLETED", required = false) String status,
                                 ToolContext context) {
        if (page < 1 || size < 1 || size > 10 || (status != null && !STATUSES.contains(status))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单查询参数无效");
        }
        long userId = (Long) context.getContext().get("userId");
        var result = orders.listUserOrders(userId, page, size, status);
        return new OrderPage(result.page(), result.size(), result.total(),
                result.items().stream().map(AgentOrderSummary::from).toList(), LocalDateTime.now());
    }

    @Tool(name = "get_my_order", description = "按订单ID查询当前登录用户本人的订单摘要；不存在或不属于本人均不可查询。", resultConverter = JsonResultConverter.class)
    public AgentOrderSummary getMyOrder(@ToolParam(description = "用户提供或本人订单列表返回的正整数订单ID") long orderId,
                                       ToolContext context) {
        if (orderId < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单ID必须为正整数");
        long userId = (Long) context.getContext().get("userId");
        return AgentOrderSummary.from(OrderResponse.from(orders.getUserOrder(userId, orderId)));
    }

    @Tool(name = "get_trade_rules", description = "查询平台已实现的交易规则资料。仅支持PAYMENT支付、CANCEL取消、TIMEOUT超时关闭；不支持退款或真实支付。返回规则ID和源码依据。", resultConverter = JsonResultConverter.class)
    public TradeRule getTradeRules(@ToolParam(description = "PAYMENT、CANCEL、TIMEOUT三者之一") String topic) {
        return switch (topic) {
            case "PAYMENT" -> new TradeRule(topic,
                    "创建订单时设置15分钟支付期限，具体期限以该订单的expiresAt为准。仅PENDING_PAYMENT且当前时间严格早于expiresAt才可模拟支付；已支付重试返回原结果。平台未接入真实支付。",
                    SERVICE_SOURCE + "#createOrder,payOrder；src/main/resources/mapper/OrderMapper.xml#markPaid");
            case "CANCEL" -> new TradeRule(topic,
                    "本人待支付订单可主动取消并归还库存；已取消重试返回原结果，其他状态冲突。取消使用现有事务释放活动名额、商品数量及适用优惠券占用。助手仅解释规则，不能执行取消。",
                    SERVICE_SOURCE + "#cancelOrder,restoreItems");
            case "TIMEOUT" -> new TradeRule(topic,
                    "PENDING_PAYMENT且expiresAt已到的订单可转为CLOSED，并归还库存和释放适用优惠券占用。由消息消费者或定时扫描触发；消息或扫描可能有处理延迟，不能仅凭CLOSED确定由哪条路径关闭。",
                    SERVICE_SOURCE + "#closeExpiredOrder；src/main/resources/mapper/OrderMapper.xml#markClosed");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的规则主题");
        };
    }

    public record OrderPage(int page, int size, long total, List<AgentOrderSummary> items, LocalDateTime queriedAt) { }

    public static class JsonResultConverter implements ToolCallResultConverter {
        private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        @Override public String convert(Object result, Type returnType) {
            // 时间统一为ISO文本，避免模型收到日期数字数组后误解支付期限。
            try { return JSON.writeValueAsString(result); }
            catch (JsonProcessingException error) { throw new IllegalStateException("工具结果序列化失败", error); }
        }
    }
}
