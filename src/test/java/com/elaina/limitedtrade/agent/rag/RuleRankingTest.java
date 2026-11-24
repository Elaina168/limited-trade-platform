package com.elaina.limitedtrade.agent.rag;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RuleRankingTest {
    private final RuleChunk payment = new RuleChunk("a", "payment", "支付", "knowledge/payment.md", 4, 4,
            "状态 PAID 与模拟支付有关。", 0);
    private final RuleChunk expiry = new RuleChunk("b", "timeout", "超时", "knowledge/timeout.md", 7, 7,
            "达到 expiresAt 后超时关单并归还库存。", 0);

    @Test void keywordsRecognizeCompleteSymbolsAndRefuseSingleGenericWords() {
        var ranking = new RuleRanking(List.of(payment, expiry));
        assertThat(ranking.keyword("ＥｘｐｉｒｅｓＡｔ", 3)).extracting(RuleChunk::id).containsExactly("b");
        assertThat(ranking.keyword("UNPAID", 3)).isEmpty();
        assertThat(ranking.keyword("库存", 3)).isEmpty();
        assertThat(ranking.keyword("库存如何在超时关单后归还", 3)).extracting(RuleChunk::path)
                .containsExactly("knowledge/timeout.md");
    }

    @Test void rrfUsesOnlyRankAndKeepsOriginalSource() {
        var merged = RuleRanking.fuse(List.of(payment.withScore(0.98), expiry.withScore(0.56)),
                List.of(expiry.withScore(6), expiry.withScore(5)), 2);
        assertThat(merged).extracting(RuleChunk::id).containsExactly("b", "a");
        assertThat(merged.getFirst()).usingRecursiveComparison().ignoringFields("score").isEqualTo(expiry);
        assertThat(merged.getFirst().score()).isCloseTo(1.0 / 62 + 1.0 / 61, within(1e-12));
        assertThat(RuleRanking.fuse(List.of(), List.of(), 3)).isEmpty();
    }
}
