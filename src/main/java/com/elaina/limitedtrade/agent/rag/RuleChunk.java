package com.elaina.limitedtrade.agent.rag;

public record RuleChunk(String id, String documentId, String title, String path,
                        int startLine, int endLine, String text, double score) {
    RuleChunk withScore(double value) {
        return new RuleChunk(id, documentId, title, path, startLine, endLine, text, value);
    }
}
