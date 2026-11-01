package com.elaina.limitedtrade.agent.rag;

public record RuleChunk(String id, String documentId, String title, String path,
                        int startLine, int endLine, String text, double score) { }
