package com.elaina.limitedtrade.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class EvaluationModelFixture implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private volatile JsonNode step;

    EvaluationModelFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::reply);
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    void step(JsonNode current) { step = current; }

    private void reply(HttpExchange exchange) throws IOException {
        try (exchange) {
            var request = json.readTree(exchange.getRequestBody());
            var script = step;
            var latest = request.required("messages").get(request.required("messages").size() - 1);
            Map<String, ?> delta;
            String finish;
            if (script.has("tool") && !latest.required("role").asText().equals("tool")) {
                var definition = Map.of("name", script.required("tool").asText(),
                        "arguments", json.writeValueAsString(script.required("arguments")));
                delta = Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "local-tool",
                        "type", "function", "function", definition)));
                finish = "tool_calls";
            } else {
                String answer = script.required("answer").asText();
                if (script.has("document")) {
                    var result = json.readTree(latest.required("content").asText());
                    String citation = "无片段";
                    for (var chunk : result.required("chunks")) {
                        if (chunk.required("documentId").asText().equals(script.required("document").asText())) {
                            citation = chunk.required("id").asText();
                            break;
                        }
                    }
                    answer = answer.replace("{source}", citation);
                }
                delta = Map.of("role", "assistant", "content", answer);
                finish = "stop";
            }
            var first = Map.of("id", "evaluation-local", "object", "chat.completion.chunk", "created", 1,
                    "model", "deepseek-flash", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
            var usage = Map.of("id", "evaluation-local", "object", "chat.completion.chunk", "created", 1,
                    "model", "deepseek-flash", "choices", List.of(),
                    "usage", Map.of("prompt_tokens", 40, "completion_tokens", 8, "total_tokens", 48));
            byte[] body = ("data: " + json.writeValueAsString(first) + "\n\ndata: " + json.writeValueAsString(usage)
                    + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    @Override public void close() { server.stop(0); }
}
