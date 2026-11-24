package com.elaina.limitedtrade.agent.rag;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleRetrievalExperimentsTest {
    @Test void rewriteAddsOnlyVerbatimPolicyTermToAnUnambiguousFollowUp() {
        assertThat(RuleQueryRewrite.resolve("还有效吗", "那还有效吗？", "平台模拟支付从2026年起要付100元吗？"))
                .isEqualTo("模拟支付 还有效吗");
        assertThat(RuleQueryRewrite.resolve("还有效吗", "那还有效吗？", "订单编号9301的模拟支付"))
                .isEqualTo("还有效吗");
        assertThat(RuleQueryRewrite.resolve("会怎样", "那会怎样？", "模拟支付和确认收货"))
                .isEqualTo("会怎样");
        assertThat(RuleQueryRewrite.resolve("还有效吗", "那信用卡分期利率呢？", "模拟支付"))
                .isEqualTo("还有效吗");
        assertThat(RuleQueryRewrite.resolve("确认收货", "那会怎样？", "模拟支付"))
                .isEqualTo("确认收货");
        assertThat(RuleQueryRewrite.resolve("还有效吗", "它还有效吗？", ""))
                .isEqualTo("还有效吗");
        String oversized = " ".repeat(400) + "还有效吗";
        assertThat(RuleQueryRewrite.resolve(oversized, "那还有效吗？", "模拟支付")).isEqualTo(oversized);
    }

    @Test void rerankUsesBodyAndKeepsStableOrderOnEqualOverlap() {
        var first = new RuleChunk("a", "a", "重复确认收货", "a.md", 1, 1, "介绍发货规则", 0.9);
        var second = new RuleChunk("b", "b", "订单", "b.md", 2, 2, "重复确认收货返回原结果", 0.7);
        var third = new RuleChunk("c", "c", "订单", "c.md", 3, 3, "重复确认收货返回原结果", 0.6);
        assertThat(RuleReranker.rank("重复确认收货", List.of(first, second, third), 2))
                .extracting(RuleChunk::id).containsExactly("b", "c");
        assertThat(RuleReranker.rank("重复确认收货", List.of(first, second, third), 1).getFirst())
                .usingRecursiveComparison().ignoringFields("score").isEqualTo(second);
        assertThat(RuleReranker.rank("？", List.of(first, second), 1)).containsExactly(first);
        assertThat(RuleReranker.rank("确认收货", List.of(), 3)).isEmpty();
    }
}
