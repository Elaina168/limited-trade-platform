"""真实双Java实例的只读联调；--failover仅中断并恢复本项目管理的node-a。"""
import argparse
import base64
import http.cookiejar
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

root = Path(__file__).resolve().parent.parent
runtime = root / "tools/native-cluster"
bases = {"node-a": "http://127.0.0.1:8081", "node-b": "http://127.0.0.1:8082", "edge": "http://127.0.0.1:18081"}
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))


def request(target, path, method="GET", data=None, headers=None):
    try:
        response = client.open(urllib.request.Request(bases[target] + path, method=method,
                                                     data=data, headers=headers or {}), timeout=8)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read()


def edge_record(trace):
    deadline = time.monotonic() + 5
    while True:
        # 响应与访问日志落盘之间可能存在很短的时间差；不解析尚未写完的最后一行。
        text = (root / "deploy/nginx-cluster/logs/access.log").read_text(encoding="utf-8")
        for line in text.splitlines(keepends=True):
            if line.endswith("\n"):
                record = json.loads(line)
                if record["traceId"] == trace:
                    return record
        if time.monotonic() >= deadline:
            raise AssertionError("Nginx访问日志中未找到本次请求")
        time.sleep(0.1)


def trace_of(headers):
    trace = headers["X-Trace-Id"]
    assert re.fullmatch("[0-9a-f]{32}", trace)
    assert len(headers.get_all("X-Trace-Id")) == 1
    return trace


def session_id():
    return next(cookie.value for cookie in cookies if cookie.name == "JSESSIONID")


def assert_central_log(trace, expected_java):
    credentials = json.loads((root / "tools/observability-runtime/grafana-credentials.json").read_text(encoding="utf-8"))
    authorization = "Basic " + base64.b64encode((credentials["username"] + ":" + credentials["password"]).encode()).decode()
    query = '{app="limited-trade"} | json | traceId="' + trace + '"'
    url = "http://127.0.0.1:3000/api/datasources/proxy/uid/limited-trade-loki/loki/api/v1/query_range?"
    url += urllib.parse.urlencode({"query": query, "since": "1h", "limit": 100})
    deadline = time.monotonic() + 30
    while True:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"Authorization": authorization}), timeout=8) as response:
            streams = json.load(response)["data"]["result"]
        rows = [(stream["stream"], json.loads(value[1])) for stream in streams for value in stream["values"]]
        if len(rows) == (2 if expected_java else 1):
            assert sum(labels["source"] == "nginx" and labels["instance"] == "cluster" for labels, _ in rows) == 1
            java_rows = [(labels, body) for labels, body in rows if labels["source"] == "java"]
            if expected_java:
                assert len(java_rows) == 1
                assert java_rows[0][0]["instance"] == expected_java
                assert java_rows[0][1]["event"] == "http_request"
                assert not java_rows[0][1].get("verification", False)
            else:
                assert not java_rows
            return
        if time.monotonic() >= deadline:
            raise AssertionError("集中日志未收齐本次真实请求")
        time.sleep(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--failover", action="store_true", help="停止并恢复本项目管理的node-a，观察入口响应")
    arguments = parser.parse_args()
    # 先检查两个HTTP端口，未启动时立即失败，不用Mock替代真实进程。
    for node in ("node-a", "node-b"):
        assert request(node, "/api/ping")[0] == 200
    assert request("edge", "/nginx-health")[0] == 200
    if arguments.failover:
        assert (runtime / "node-a.json").exists(), "故障演练只允许管理脚本创建的实例"
    account = json.loads((root / "config/local-accounts.txt").read_text(encoding="utf-8-sig"))["user"]
    form = urllib.parse.urlencode({"username": account["username"], "password": account["password"]}).encode()
    authenticated = False
    traces = {}
    report = {"failover": arguments.failover, "gatewayStatuses": []}
    try:
        assert request("node-a", "/api/auth/me")[0] == 401
        status, _, body = request("node-a", "/api/auth/csrf")
        assert status == 200
        csrf = json.loads(body)
        old_session = session_id()
        assert request("node-b", "/api/auth/login", "POST", form,
                       {"Content-Type": "application/x-www-form-urlencoded", csrf["headerName"]: csrf["token"]})[0] == 204
        authenticated = True
        current_session = session_id()
        assert old_session != current_session
        for node in ("node-a", "node-b"):
            status, _, body = request(node, "/api/auth/me")
            assert status == 200
            identity = json.loads(body)
            assert identity["username"] == account["username"] and identity["role"] == "USER"
            assert request(node, "/api/admin/products")[0] == 403
        # 使用登录前的旧编号单独访问，不污染正常客户端的CookieJar。
        try:
            urllib.request.urlopen(urllib.request.Request(bases["node-a"] + "/api/auth/me",
                                                         headers={"Cookie": "JSESSIONID=" + old_session}), timeout=8)
        except urllib.error.HTTPError as error:
            assert error.code == 401
            error.close()
        else:
            raise AssertionError("登录前旧会话仍可访问身份接口")
        assert request("node-a", "/api/auth/logout", "POST", headers={csrf["headerName"]: csrf["token"]})[0] == 403
        assert request("edge", "/api/auth/logout", "POST")[0] == 403

        order_lists = []
        for node in ("node-a", "node-b"):
            status, _, body = request(node, "/api/orders")
            assert status == 200
            order_lists.append(json.loads(body))
        assert order_lists[0] == order_lists[1]
        for _ in range(6):
            status, headers, body = request("edge", "/api/auth/me")
            assert status == 200 and json.loads(body)["id"] == identity["id"]
            trace = trace_of(headers)
            backend = edge_record(trace)["upstream"]
            traces[backend] = trace
        assert set(traces) == {"127.0.0.1:8081", "127.0.0.1:8082"}
        for address, node in (("127.0.0.1:8081", "node-a"), ("127.0.0.1:8082", "node-b")):
            assert_central_log(traces[address], node)

        if arguments.failover:
            # 恢复放在finally，即使演练断言失败也不有意让实例停留在故障状态。
            stopped = False
            try:
                subprocess.run(["pwsh.exe", "-NoProfile", "-File", str(root / "scripts/stop-native-cluster.ps1"), "-Instance", "node-a"], check=True, cwd=root)
                stopped = True
                failure_trace = None
                for _ in range(6):
                    status, headers, body = request("edge", "/api/auth/me")
                    report["gatewayStatuses"].append(status)
                    assert status in (200, 502)
                    trace = trace_of(headers)
                    if status == 502:
                        failure_trace = trace
                    else:
                        assert json.loads(body)["id"] == identity["id"]
                        assert edge_record(trace)["upstream"] == "127.0.0.1:8082"
                assert report["gatewayStatuses"].count(502) == 1
                assert report["gatewayStatuses"].count(200) == 5
                assert_central_log(failure_trace, None)
            finally:
                if stopped:
                    subprocess.run(["pwsh.exe", "-NoProfile", "-File", str(root / "scripts/start-native-cluster.ps1"), "-Instance", "node-a"], check=True, cwd=root)
            deadline = time.monotonic() + 20
            while True:
                status, headers, body = request("edge", "/api/auth/me")
                assert status == 200 and json.loads(body)["id"] == identity["id"]
                if edge_record(trace_of(headers))["upstream"] == "127.0.0.1:8081":
                    break
                if time.monotonic() >= deadline:
                    raise AssertionError("恢复的node-a未重新接收入口请求")
                time.sleep(1)

        status, _, body = request("node-b", "/api/auth/csrf")
        assert status == 200
        fresh = json.loads(body)
        assert request("node-a", "/api/auth/logout", "POST", headers={fresh["headerName"]: fresh["token"]})[0] == 204
        authenticated = False
        for node in ("node-a", "node-b"):
            assert request(node, "/api/auth/me")[0] == 401
        for path in (root / "logs/node-a.log", root / "logs/node-b.log", root / "deploy/nginx-cluster/logs/access.log"):
            text = path.read_text(encoding="utf-8")
            assert all(secret not in text for secret in (account["password"], old_session, current_session, csrf["token"], fresh["token"]))
        report.update({"sharedSession": True, "csrf": True, "roles": True, "readOnlyOrders": True,
                       "bothUpstreams": True, "realCentralLogs": True, "crossInstanceLogout": True})
        (runtime / "verification.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print("PASS real Java cluster: shared login/CSRF/roles/orders/logout, two upstreams and Grafana correlation.")
        if arguments.failover:
            print("PASS node-a stopped: one 502, five successes on node-b; node-a restored with identity retained.")
    finally:
        if authenticated:
            status, _, body = request("node-b", "/api/auth/csrf")
            assert status == 200
            fresh = json.loads(body)
            assert request("node-b", "/api/auth/logout", "POST", headers={fresh["headerName"]: fresh["token"]})[0] == 204
