package com.elaina.limitedtrade.agent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class RuleDocumentsTest {
    @Test void publishedCorpusIncludesExpandedStorefrontRules() throws Exception {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var documents = resolver.getResources("classpath:knowledge/*.md");
        assertThat(documents).extracting(org.springframework.core.io.Resource::getFilename)
                .contains("catalog-search.md", "activity-availability.md", "coupon-claim.md", "coupon-use.md",
                        "cart-checkout.md", "address-book.md", "order-retry.md", "assistant-history.md");
        var chunks = new java.util.ArrayList<RuleChunk>();
        for (var document : documents)
            chunks.addAll(RuleDocuments.split("knowledge/" + document.getFilename(), document, 320));
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(45)
                .extracting(RuleChunk::id).doesNotHaveDuplicates();
    }

    @Test void chunksPreserveHeadingLinesStableIdsAndCompleteText() throws Exception {
        var text = "# 支付规则\n\n## 支付期限\n创建后15分钟内支付。\n" + "规则说明。".repeat(60) + "\n\n## 真实支付\n目前仅模拟支付。\n";
        var resource = new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8));
        var first = RuleDocuments.split("knowledge/payment.md", resource, 100);
        var second = RuleDocuments.split("knowledge/payment.md", resource, 100);
        assertThat(first).hasSizeGreaterThan(2).isEqualTo(second);
        assertThat(first).allSatisfy(chunk -> {
            assertThat(chunk.path()).isEqualTo("knowledge/payment.md");
            assertThat(chunk.startLine()).isPositive();
            assertThat(chunk.endLine()).isGreaterThanOrEqualTo(chunk.startLine());
            assertThat(chunk.text().length()).isLessThanOrEqualTo(100);
        });
        assertThat(first).anySatisfy(chunk -> assertThat(chunk.title()).contains("真实支付"));
        assertThat(first.stream().map(RuleChunk::text).reduce("", String::concat))
                .contains("创建后15分钟内支付。", "目前仅模拟支付。");
    }
}
