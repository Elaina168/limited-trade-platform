package com.elaina.limitedtrade.agent.rag;

import java.util.List;
import java.util.regex.Pattern;

public final class RuleQueryRewrite {
    private static final String GENERIC = "(?:还有效吗|还适用吗|还能用吗|还能操作吗|会怎样|会重复处理吗|会再执行一次吗)";
    private static final Pattern FOLLOW_UP = Pattern.compile("^(?:那|它|这条|这个|这种情况)(?:规则)?" + GENERIC + "$");
    private static final Pattern TOOL_QUERY = Pattern.compile("^(?:" + GENERIC + "|重复处理|还能吗)$");
    private static final Pattern ORDER_REFERENCE =
            Pattern.compile("订单(?:号|编号)?\\s*[:：#]?\\s*[0-9]+|这笔|那笔|哪笔");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\p{P}]");
    private static final List<List<String>> TOPICS = List.of(
            List.of("模拟支付", "支付截止", "付款期限", "重复支付"),
            List.of("主动取消", "待支付取消", "重复取消", "取消订单"),
            List.of("超时关单", "超时关闭", "关单", "定时扫描"),
            List.of("确认收货", "登记发货", "地址快照"),
            List.of("订单归属", "本人订单查询", "订单分页"));

    private RuleQueryRewrite() { }

    public static String resolve(String query, String currentQuestion, String previousQuestion) {
        // 只扩充固定的泛化追问。先前消息只用于选取公开术语，不直接拼入问题。
        if (query.length() > 400 || !FOLLOW_UP.matcher(SEPARATORS.matcher(currentQuestion).replaceAll("")).matches()
                || !TOOL_QUERY.matcher(SEPARATORS.matcher(query).replaceAll("")).matches()
                || ORDER_REFERENCE.matcher(previousQuestion).find()) return query;

        var matching = TOPICS.stream()
                .map(topic -> topic.stream().filter(previousQuestion::contains).findFirst())
                .flatMap(java.util.Optional::stream).toList();
        if (matching.size() != 1) return query;
        String expanded = matching.getFirst() + " " + query.strip();
        return expanded.length() <= 400 ? expanded : query;
    }
}
