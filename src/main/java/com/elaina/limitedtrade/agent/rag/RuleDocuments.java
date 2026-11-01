package com.elaina.limitedtrade.agent.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;

public final class RuleDocuments {
    private RuleDocuments() { }

    public static List<RuleChunk> split(String path, Resource resource, int maxChars) throws IOException {
        String[] lines = resource.getContentAsString(StandardCharsets.UTF_8).split("\\R", -1);
        var chunks = new ArrayList<RuleChunk>();
        String documentTitle = path;
        String heading = path;
        var body = new StringBuilder();
        int start = 0;
        int end = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("#")) {
                add(chunks, path, heading, start, end, body);
                if (line.startsWith("# ")) documentTitle = line.substring(2).strip();
                heading = documentTitle + " / " + line.replaceFirst("^#+\\s*", "");
                continue;
            }
            if (line.isBlank()) continue;
            if (!body.isEmpty() && body.length() + 1 + line.length() > maxChars) {
                add(chunks, path, heading, start, end, body);
            }
            for (int offset = 0; offset < line.length();) {
                if (body.isEmpty()) start = i + 1;
                int available = maxChars - body.length() - (body.isEmpty() ? 0 : 1);
                if (available == 0) { add(chunks, path, heading, start, end, body); continue; }
                int count = Math.min(available, line.length() - offset);
                if (!body.isEmpty()) body.append('\n');
                body.append(line, offset, offset + count);
                end = i + 1;
                offset += count;
                if (offset < line.length()) add(chunks, path, heading, start, end, body);
            }
        }
        add(chunks, path, heading, start, end, body);
        return List.copyOf(chunks);
    }

    private static void add(List<RuleChunk> chunks, String path, String title, int start, int end, StringBuilder body) {
        if (body.isEmpty()) return;
        String id = UUID.nameUUIDFromBytes((path + ":" + start + ":" + end + ":" + body)
                .getBytes(StandardCharsets.UTF_8)).toString();
        chunks.add(new RuleChunk(id, path.substring(path.lastIndexOf('/') + 1).replace(".md", ""),
                title, path, start, end, body.toString(), 0));
        body.setLength(0);
    }
}
