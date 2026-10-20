"""验证文件采集和Grafana查询；Java记录为明确标记的受控样本，不写交易数据。"""
import base64
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
runtime = root / "tools/observability-runtime"


def request(url, authorization=None):
    headers = {} if authorization is None else {"Authorization": authorization}
    try:
        response = urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read()


def query(expression, authorization=None):
    # Grafana代理与直接查询使用相同的LogQL，检查整条查询链路。
    base = "http://127.0.0.1:3100" if authorization is None else "http://127.0.0.1:3000/api/datasources/proxy/uid/limited-trade-loki"
    parameters = urllib.parse.urlencode({"query": expression, "limit": 100, "since": "1h"})
    status, _, body = request(base + "/loki/api/v1/query_range?" + parameters, authorization)
    assert status == 200, ("query status", status)
    return json.loads(body)["data"]["result"]


def await_count(expression, expected, authorization=None):
    deadline = time.monotonic() + 35
    while True:
        streams = query(expression, authorization)
        count = sum(len(stream["values"]) for stream in streams)
        if count == expected:
            return streams
        if time.monotonic() >= deadline:
            raise AssertionError(("log count", count, "expected", expected))
        time.sleep(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nginx-mode", choices=("single", "cluster"), default="single")
    arguments = parser.parse_args()
    assert request("http://127.0.0.1:3100/ready")[0] == 200
    assert request("http://127.0.0.1:12345/-/ready")[0] == 200
    assert request("http://127.0.0.1:3000/api/health")[0] == 200
    credentials = json.loads((runtime / "grafana-credentials.json").read_text(encoding="utf-8"))
    authorization = "Basic " + base64.b64encode((credentials["username"] + ":" + credentials["password"]).encode()).decode()
    assert request("http://127.0.0.1:3000/api/datasources/uid/limited-trade-loki")[0] == 401
    status, _, body = request("http://127.0.0.1:3000/api/datasources/uid/limited-trade-loki/health", authorization)
    assert status == 200 and json.loads(body)["status"] == "OK"
    status, _, body = request("http://127.0.0.1:3000/api/dashboards/uid/limited-trade-logs", authorization)
    assert status == 200 and json.loads(body)["dashboard"]["uid"] == "limited-trade-logs"

    # 入口必须已由本项目Nginx启动；接口只读，后端未启动时真实产生502。
    gateway_port = 18081 if arguments.nginx_mode == "cluster" else 18080
    gateway_status, gateway_headers, _ = request(f"http://127.0.0.1:{gateway_port}/api/admin/products")
    assert gateway_status in (401, 502), gateway_status
    trace = gateway_headers["X-Trace-Id"]
    assert re.fullmatch("[0-9a-f]{32}", trace)
    fixture = root / "logs" / ("observability-verification-" + uuid.uuid4().hex + ".log")
    expression = '{app="limited-trade"} | json | traceId="' + trace + '"'
    try:
        with fixture.open("a", encoding="utf-8") as file:
            for instance in ("verification-a", "verification-b"):
                file.write(json.dumps({"@timestamp": datetime.now(timezone.utc).isoformat(), "instance": instance,
                                       "event": "observability_probe", "verification": True, "traceId": trace,
                                       "level": "ERROR", "status": 503, "message": "受控采集验证，不是真实交易异常"}, ensure_ascii=False) + "\n")
        streams = await_count(expression, 3, authorization)
        labels = {(stream["stream"]["source"], stream["stream"]["instance"]) for stream in streams}
        assert labels == {("java", "verification-a"), ("java", "verification-b"), ("nginx", arguments.nginx_mode)}
        # LogQL的json阶段会把正文临时展开为查询标签；索引标签要用series接口单独检查。
        series_parameters = urllib.parse.urlencode({"match[]": '{app="limited-trade"}'})
        status, _, body = request("http://127.0.0.1:3100/loki/api/v1/series?" + series_parameters)
        assert status == 200
        indexed = json.loads(body)["data"]
        assert indexed and all(set(labels) == {"app", "source", "instance", "service_name"} for labels in indexed)
        await_count('{app="limited-trade",source="java"} | json | traceId="' + trace + '" | status >= 500', 2, authorization)
        await_count('{app="limited-trade",source="nginx"} | json | traceId="' + trace + '" | status >= 400', 1, authorization)
        # 保存检查点信息，供重启验证使用；不包含凭据或会话数据。
        (runtime / "verification.json").write_text(json.dumps({"traceId": trace, "gatewayStatus": gateway_status,
                                                              "fixture": str(fixture)}), encoding="utf-8")
        print("PASS 文件→Alloy→Loki→Grafana：两个受控Java实例、真实Nginx请求、traceId关联、错误查询、标签和自动装配。")
    finally:
        fixture.unlink()
