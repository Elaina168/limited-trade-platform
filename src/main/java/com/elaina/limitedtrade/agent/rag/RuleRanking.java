package com.elaina.limitedtrade.agent.rag;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class RuleRanking {
    private static final int RRF_K = 60;
    private static final Pattern SYMBOL = Pattern.compile("[a-z0-9_]+(?:-[a-z0-9_]+)*");
    private static final Set<String> EXACT = Set.of(
            "expiresat", "pending_payment", "paid", "canceled", "closed", "shipped", "completed", "rabbitmq",
            "markpaid", "markcanceled", "markclosed", "markshipped", "markcompleted", "findownedorder",
            "requestid", "total", "404", "支付截止", "付款期限", "模拟支付", "定时扫描", "超时关单",
            "主动取消", "确认收货", "地址快照", "优惠券占用", "活动名额", "重复支付", "重复取消",
            "商品分类", "商品搜索", "购物车结算", "收货地址", "默认地址", "满减门槛", "领取时间",
            "发放名额", "使用截止", "请求标识", "retry-after", "会话历史", "模型上下文");
    private static final Set<String> RELATED = Set.of(
            "支付", "付款", "未付款", "待支付", "取消", "关单", "关闭", "重复", "库存", "回补", "扫描",
            "优惠券", "订单状态", "确认", "收货时间", "发货", "运单号", "手机号", "分页", "当前页",
            "订单摘要", "归属", "本人订单", "状态冲突", "资格", "截止", "有效期", "幂等", "扣减", "收货");
    private static final Comparator<RuleChunk> BEST_FIRST =
            Comparator.comparingDouble(RuleChunk::score).reversed().thenComparing(RuleChunk::id);

    private final List<IndexedChunk> indexed;
    private final HashMap<String, Integer> frequencies = new HashMap<>();

    RuleRanking(List<RuleChunk> chunks) {
        indexed = chunks.stream().map(chunk -> new IndexedChunk(chunk, terms(chunk.title() + "\n" + chunk.text()))).toList();
        indexed.forEach(chunk -> chunk.terms().forEach(term -> frequencies.merge(term, 1, Integer::sum)));
    }

    List<RuleChunk> keyword(String query, int limit) {
        var requested = terms(query).stream().sorted().toList();
        return indexed.stream().map(row -> {
            int related = 0;
            boolean exact = false;
            double score = 0;
            for (String term : requested) {
                if (!row.terms().contains(term)) continue;
                boolean strong = EXACT.contains(term);
                exact |= strong;
                if (!strong) related++;
                score += (strong ? 2 : 1) * Math.log1p((double) indexed.size() / frequencies.get(term));
            }
            // 单个普通词不能独立形成证据候选。
            return row.chunk().withScore(exact || related >= 2 ? score : 0);
        }).filter(chunk -> chunk.score() > 0).sorted(BEST_FIRST).limit(limit).toList();
    }

    static List<RuleChunk> fuse(List<RuleChunk> vector, List<RuleChunk> keyword, int limit) {
        var originals = new HashMap<String, RuleChunk>();
        var scores = new HashMap<String, Double>();
        for (var route : List.of(vector, keyword)) {
            var contributed = new HashSet<String>();
            for (int i = 0; i < route.size(); i++) {
                var chunk = route.get(i);
                if (!contributed.add(chunk.id())) continue;
                originals.putIfAbsent(chunk.id(), chunk);
                scores.merge(chunk.id(), 1.0 / (RRF_K + i + 1), Double::sum);
            }
        }
        return originals.values().stream().map(chunk -> chunk.withScore(scores.get(chunk.id())))
                .sorted(BEST_FIRST).limit(limit).toList();
    }

    private static Set<String> terms(String input) {
        String text = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        var found = new HashSet<String>();
        var symbols = SYMBOL.matcher(text).results().map(result -> result.group()).toList();
        for (String term : EXACT) {
            if (term.charAt(0) < 128 ? symbols.contains(term) : text.contains(term)) found.add(term);
        }
        for (String term : RELATED) if (text.contains(term)) found.add(term);
        return found;
    }

    private record IndexedChunk(RuleChunk chunk, Set<String> terms) { }
}
