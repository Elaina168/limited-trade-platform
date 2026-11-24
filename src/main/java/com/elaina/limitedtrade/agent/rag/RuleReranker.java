package com.elaina.limitedtrade.agent.rag;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class RuleReranker {
    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}a-z0-9_]+");

    private RuleReranker() { }

    static List<RuleChunk> rank(String query, List<RuleChunk> candidates, int limit) {
        var requested = pairs(query);
        if (requested.isEmpty()) return candidates.stream().limit(limit).toList();
        return candidates.stream().map(chunk -> {
            var body = pairs(chunk.text());
            long overlap = requested.stream().filter(body::contains).count();
            return chunk.withScore((double) overlap / requested.size());
        }).sorted(Comparator.comparingDouble(RuleChunk::score).reversed()).limit(limit).toList();
    }

    private static Set<String> pairs(String text) {
        var result = new HashSet<String>();
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        TOKEN.matcher(normalized).results().forEach(match -> {
            int[] codepoints = match.group().codePoints().toArray();
            for (int i = 0; i + 1 < codepoints.length; i++) result.add(new String(codepoints, i, 2));
        });
        return result;
    }
}
