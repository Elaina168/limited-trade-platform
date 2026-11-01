package com.elaina.limitedtrade.agent.rag;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BgeEmbeddingModelTest {
    @Test void actualModelUses512DimensionalNormalizedClsVectors() throws Exception {
        try (var model = new BgeEmbeddingModel(Path.of("tools/models/bge-small-zh-v1.5"))) {
            var first = model.embed("订单支付截止时间为创建后十五分钟");
            var second = model.embed("订单支付截止时间为创建后十五分钟");
            assertThat(first).hasSize(512).containsExactly(second);
            double square = 0;
            for (float value : first) square += value * value;
            assertThat(square).isCloseTo(1, within(0.00001));
            assertThat(model.dimensions()).isEqualTo(512);
            assertThat(model.fingerprint()).contains("cls-l2", "69a0b846");
        }
    }

    @Test void missingModelFailsInsteadOfDownloadingOrUsingAnotherModel() {
        assertThatThrownBy(() -> new BgeEmbeddingModel(Path.of("tools/not-a-model")))
                .isInstanceOf(java.io.IOException.class);
    }
}
