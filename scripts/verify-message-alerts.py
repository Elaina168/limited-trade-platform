"""用明确标记的受控监控样本验证文件采集、LogQL和Grafana告警，不写交易或消费队列。"""
import base64
from datetime import datetime, timezone
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
credentials = json.loads((root / "tools/observability-runtime/grafana-credentials.json").read_text(encoding="utf-8"))
authorization = "Basic " + base64.b64encode((credentials["username"] + ":" + credentials["password"]).encode()).decode()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get_json(path):
    request = urllib.request.Request("http://127.0.0.1:3000" + path, headers={"Authorization": authorization})
    with opener.open(request, timeout=5) as response:
        return json.load(response)


def states():
    groups = get_json("/api/prometheus/grafana/api/v1/rules")["data"]["groups"]
    return {rule["name"]: {"state": rule["state"], "health": rule["health"], "lastError": rule.get("lastError", "")}
            for group in groups for rule in group["rules"]}


def wait_state(name, target, emit=None, timeout=180):
    deadline = time.monotonic() + timeout
    last_print = 0
    while True:
        if emit is not None:
            emit()
        rule = states()[name]
        assert rule["health"] == "ok", rule
        if rule["state"] == target:
            print(name, target, flush=True)
            return
        if time.monotonic() >= deadline:
            raise AssertionError((name, target, rule))
        if time.monotonic() - last_print >= 15:
            print("waiting", name, rule["state"], "->", target, flush=True)
            last_print = time.monotonic()
        time.sleep(3)


if __name__ == "__main__":
    assert get_json("/api/health")["database"] == "ok"
    dashboard = get_json("/api/dashboards/uid/limited-trade-messages")["dashboard"]
    assert len(dashboard["panels"]) == 12
    rules = get_json("/api/v1/provisioning/alert-rules")
    assert {"trade-message-health", "trade-message-sampling-missing"}.issubset({rule["uid"] for rule in rules})
    # 逐一执行看板查询，确认LogQL可以解析。缺样本时空结果不会被替换为0。
    for panel in dashboard["panels"]:
        query = urllib.parse.urlencode({"query": panel["targets"][0]["expr"]})
        with opener.open("http://127.0.0.1:3100/loki/api/v1/query?" + query, timeout=5) as response:
            assert json.load(response)["status"] == "success"

    fixture = root / "logs" / ("message-monitor-verification-" + uuid.uuid4().hex + ".log")

    def emit(alert_count=0):
        record = {"@timestamp": datetime.now(timezone.utc).isoformat(), "instance": "message-monitor-verification",
                  "event": "order_message_health", "verification": True,
                  "message": "受控告警验证样本，不是真实交易故障", "level": "WARN" if alert_count else "INFO",
                  "outboxPending": 0, "outboxRetrying": 0, "storedFailedAttempts": 0, "oldestPendingSeconds": 0,
                  "overdueOrders": 0, "oldestOverdueSeconds": 0, "closedLastHour": 0,
                  "closedLastHourMaxDelaySeconds": 0, "queuesAvailable": 1, "waitReady": 0, "readyReady": 0,
                  "failedReady": alert_count, "readyConsumers": 1, "alertCount": alert_count,
                  "alerts": ["FAILED_MESSAGES"] if alert_count else []}
        with fixture.open("a", encoding="utf-8") as file:
            file.write(json.dumps(record, ensure_ascii=False) + "\n")

    try:
        emit()
        wait_state("订单消息链路异常", "inactive", emit)
        wait_state("订单消息监控采样缺失", "inactive", emit)
        wait_state("订单消息链路异常", "firing", lambda: emit(1))
        wait_state("订单消息链路异常", "inactive", emit)
        print("暂停受控样本，等待2分钟窗口及20秒持续条件；不是停止Java或消息服务。", flush=True)
        wait_state("订单消息监控采样缺失", "firing", timeout=200)
        wait_state("订单消息监控采样缺失", "inactive", emit)
        report = {"dashboardPanels": 12, "provisionedRules": 2, "healthAlertFiredAndRecovered": True,
                  "samplingMissingFiredAndRecovered": True, "controlledSamples": True,
                  "realBusinessFailure": False}
        (root / "tools/message-alerts-result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report), flush=True)
    finally:
        fixture.unlink(missing_ok=True)
