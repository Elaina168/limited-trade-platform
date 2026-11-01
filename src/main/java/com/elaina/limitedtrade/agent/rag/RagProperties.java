package com.elaina.limitedtrade.agent.rag;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.nio.file.Path;
import org.springframework.boot.context.properties.bind.DefaultValue;

public record RagProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("tools/models/bge-small-zh-v1.5") Path modelDirectory,
        @DefaultValue("tools/rag-index/rules.json") Path indexFile,
        @DefaultValue("320") @Min(80) @Max(400) int chunkChars,
        @DefaultValue("3") @Min(1) @Max(5) int topK,
        @DefaultValue("0.55") @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold,
        @DefaultValue("false") boolean rebuildIndex) { }
