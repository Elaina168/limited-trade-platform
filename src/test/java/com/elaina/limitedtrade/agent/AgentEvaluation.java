package com.elaina.limitedtrade.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

// 仅供测试评估使用，自动约束结果不代表完整的语义正确性。
final class AgentEvaluation {
    static Map<String, Boolean> evaluate(JsonNode expected, AgentChatResponse response,
                                         Integer errorStatus, boolean unchanged) {
        var checks = new LinkedHashMap<String, Boolean>();
        if (expected.has("anyOf")) {
            checks.put("acceptedOutcome", StreamSupport.stream(expected.get("anyOf").spliterator(), false)
                    .anyMatch(outcome -> evaluate(outcome, response, errorStatus, unchanged).values().stream().allMatch(Boolean::booleanValue)));
            return checks;
        }
        checks.put("status", expected.path("status").asInt(200) == (errorStatus == null ? 200 : errorStatus)
                && (errorStatus != null || response != null));
        checks.put("ordersUnchanged", unchanged);
        if (response == null) return checks;
        checks.put("allowedTools", response.toolsUsed().stream().allMatch(
                List.of("list_my_orders", "get_my_order", "search_trade_rules")::contains));
        if (expected.path("noTools").asBoolean()) checks.put("noTools", response.toolsUsed().isEmpty() && response.toolCalls() == 0);
        if (expected.has("tools")) checks.put("tools", values(expected.get("tools")).allMatch(response.toolsUsed()::contains));
        if (expected.has("forbiddenTools")) checks.put("forbiddenTools", values(expected.get("forbiddenTools")).noneMatch(response.toolsUsed()::contains));
        if (expected.has("contains")) checks.put("contains", values(expected.get("contains")).allMatch(response.answer()::contains));
        if (expected.has("any")) checks.put("wording", values(expected.get("any")).anyMatch(response.answer()::contains));
        if (expected.has("forbidden")) checks.put("forbidden", values(expected.get("forbidden")).noneMatch(response.answer()::contains));
        if (expected.has("documents")) {
            checks.put("documents", values(expected.get("documents")).allMatch(id -> response.knowledgeSources().stream()
                    .anyMatch(chunk -> chunk.documentId().equals(id))));
            checks.put("citation", response.knowledgeSources().stream().anyMatch(chunk -> response.answer().contains(chunk.id())));
        }
        if (expected.path("noSources").asBoolean()) checks.put("noSources", response.knowledgeSources().isEmpty() && response.sources().isEmpty());
        if (expected.path("paymentConclusionRequiresEvidence").asBoolean()) {
            // 这是当前固定样本的措辞约束，不是生产代码的语义分类器。
            boolean conclusion = List.of("无法支付", "不能支付", "不可支付", "不能付款", "无法付款", "不允许支付", "可以支付", "允许支付", "可以付款", "不支持支付")
                    .stream().anyMatch(response.answer()::contains);
            checks.put("paymentEvidence", !conclusion || response.toolsUsed().contains("search_trade_rules")
                    && response.knowledgeSources().stream().anyMatch(chunk -> chunk.documentId().equals("payment") && response.answer().contains(chunk.id())));
        }
        return checks;
    }

    private static java.util.stream.Stream<String> values(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> report(String model, List<Map<String, Object>> scenarios, boolean complete) {
        var steps = scenarios.stream().flatMap(row -> ((List<Map<String,Object>>) row.get("steps")).stream()).toList();
        var requested = steps.stream().filter(row -> !row.get("state").equals("NOT_RUN")).toList();
        var responses = requested.stream().filter(row -> row.containsKey("response"))
                .map(row -> (AgentChatResponse) row.get("response")).toList();
        long passed = scenarios.stream().filter(row -> ((List<Map<String,Object>>) row.get("steps")).stream()
                .allMatch(step -> step.get("state").equals("COMPLETE")
                        && ((Map<String,Boolean>) step.get("checks")).values().stream().allMatch(Boolean::booleanValue))).count();
        var report = new LinkedHashMap<String, Object>();
        report.put("generatedAt", Instant.now().toString());
        report.put("evaluationVersion", 2);
        report.put("model", model);
        report.put("complete", complete);
        report.put("scenarioCount", scenarios.size());
        report.put("passedScenarios", passed);
        long completedScenarios = scenarios.stream().filter(row -> ((List<Map<String,Object>>) row.get("steps")).stream()
                .allMatch(step -> step.get("state").equals("COMPLETE"))).count();
        report.put("failedScenarios", completedScenarios - passed);
        report.put("incompleteScenarios", scenarios.size() - completedScenarios);
        report.put("requests", (long) requested.size());
        report.put("knownTokens", responses.stream().filter(r -> r.totalTokens() != null).mapToLong(AgentChatResponse::totalTokens).sum());
        report.put("unknownTokenRequests", requested.size() - responses.stream().filter(r -> r.totalTokens() != null).count());
        report.put("successfulResponseCount", responses.size());
        report.put("averageSuccessfulDurationMs", responses.isEmpty() ? null : responses.stream().mapToLong(AgentChatResponse::durationMs).average().orElseThrow());
        report.put("manualReview", "PENDING");
        report.put("scenarios", scenarios);
        return report;
    }
}
