"""启动本机隔离微服务，执行 HTTP、Nacos、Sentinel 和 MQ 验收，保留同一运行的证据。"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
PORTS = {"nacos": 18760, "nacos-console": 18759, "mysql": 18762, "redis": 18763,
         "rabbitmq": 18764, "rabbitmq-dist": 18765, "epmd": 18766,
         "nacos-grpc": 19760, "nacos-peer": 19761, "product-a": 18770, "product-b": 18771,
         "marketing": 18772, "order": 18780, "gateway": 18800}
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
BUYER = {"Authorization": "Bearer lab-buyer-1"}
AGGREGATE = "/api/aggregate/products/1"
DEFAULTS_PATH = ROOT / "scripts" / "nacos-defaults.properties"
DEFAULTS = dict(line.split("=", 1) for line in DEFAULTS_PATH.read_text(encoding="utf-8").splitlines() if line)
NACOS_CONFIG_PATH = "/nacos/v3/admin/cs/config"


def request(node, path, method="GET", body=None, headers=None, form=False):
    payload = None
    if body is not None:
        payload = (urllib.parse.urlencode(body) if form else json.dumps(body, ensure_ascii=False)).encode("utf-8")
    req = urllib.request.Request(f"http://127.0.0.1:{PORTS[node]}{path}", payload, method=method,
                                 headers={"Accept": "application/json",
                                          "Content-Type": "application/x-www-form-urlencoded" if form else "application/json",
                                          **(headers or {})})
    started = time.perf_counter()
    try:
        response = OPENER.open(req, timeout=8)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode("utf-8")
        content = json.loads(raw) if raw and "json" in response.headers.get("Content-Type", "") else raw
        return {"status": response.status, "headers": {k.lower(): v for k, v in response.headers.items()},
                "body": content, "ms": (time.perf_counter() - started) * 1000}


def wait_for(description, probe, predicate, seconds=25, interval=.12):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = probe()
            if predicate(last):
                return last
        except (urllib.error.URLError, TimeoutError) as error:
            last = str(error)
        time.sleep(interval)
    raise AssertionError(f"{description} 超时，最后结果 {last}")


def aggregate(mode="parallel", headers=None):
    query = "?mode=serial" if mode == "serial" else ""
    return request("gateway", AGGREGATE + query, headers=headers)


def aggregate_is_ready(result):
    return result["status"] == 200 and result["body"]["marketingDegraded"] is False


def publish(properties):
    body = {"dataId": "cloud-lab.properties", "groupName": "DEFAULT_GROUP",
            "type": "properties", "content": "\n".join(f"{key}={value}" for key, value in properties.items()) + "\n"}
    result = request("nacos", NACOS_CONFIG_PATH, "POST", body, form=True)
    assert result["status"] == 200 and result["body"]["code"] == 0 and result["body"]["data"] is True, result


def port_open(host, port):
    with socket.socket() as client:
        client.settimeout(.3)
        return client.connect_ex((host, port)) == 0


class Verification:
    def __init__(self, run_id):
        self.run_id = run_id
        self.directory = ROOT / "runtime" / f"lab-{run_id}"
        self.powershell = os.environ.get("LAB_PWSH") or shutil.which("pwsh.exe")
        if not self.powershell:
            raise RuntimeError("请在 PowerShell 7.4+ 所在终端运行，或设置 LAB_PWSH。")
        self.report = {"startedAt": datetime.now(timezone.utc).isoformat(), "runId": run_id, "checks": {}}
        self.config = DEFAULTS.copy()

    def command(self, action, *options):
        args = [self.powershell, "-NoProfile", "-File", str(ROOT / "scripts" / "lab.ps1"),
                "-Action", action, "-RunId", self.run_id, *options]
        log = self.directory / f"command-{action}-{time.time_ns()}.log"
        # 将输出直接写入文件，避免子进程继承管道导致等待EOF。
        with log.open("wb") as output:
            result = subprocess.run(args, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, check=False)
        print(log.read_text(encoding="utf-8").strip(), flush=True)
        if result.returncode:
            raise RuntimeError(f"lab.ps1 {action} 失败，退出码 {result.returncode}。日志 {log}")

    def mark(self, name, value):
        self.report["checks"][name] = value
        self.save()
        print(f"PASS {name}", flush=True)

    def save(self):
        (self.directory / "verification.json").write_text(json.dumps(self.report, ensure_ascii=False, indent=2), encoding="utf-8")

    def sql(self, query):
        args = [self.connection["mysql"], "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
                f"--port={PORTS['mysql']}", f"--user={self.connection['dbUser']}",
                "--default-character-set=utf8mb4", "--batch", "--skip-column-names", "cloud_mall_lab"]
        result = subprocess.run(args, input=query, encoding="utf-8", capture_output=True, timeout=8,
                                env={**os.environ, "MYSQL_PWD": self.connection["dbPassword"]}, check=True)
        return [line.split("\t") for line in result.stdout.splitlines()]

    def pid(self, node):
        record = self.directory / f"{node}.process.json"
        return json.loads(record.read_text(encoding="utf-8"))["processId"]

    def update(self, **values):
        self.config.update(values)
        publish(self.config)

    def trace_lines(self, node, trace):
        record = json.loads((self.directory / f"{node}.process.json").read_text(encoding="utf-8"))
        return [line for output in ("stdout", "stderr")
                for line in Path(record[output]).read_text(encoding="utf-8").splitlines() if trace in line]

    def verify_topology_and_config(self):
        supplied_trace = uuid4().hex
        first = wait_for("通过网关完成聚合", lambda: aggregate(headers={"X-Trace-Id": supplied_trace}), aggregate_is_ready)
        trace = first["headers"]["x-trace-id"]
        assert re.fullmatch(r"[a-f0-9]{32}", trace) and trace != supplied_trace, first
        product = first["body"]["product"]
        assert product["id"] == 1 and product["stock"] == 50, product
        assert first["body"]["activities"] and first["body"]["coupons"], first
        self.coupon_id = first["body"]["coupons"][0]["id"]
        names = ("gateway", "order", "marketing", product["instance"])
        trace_logs = {node: wait_for(f"{node} 追踪日志", lambda: self.trace_lines(node, trace), bool) for node in names}
        self.mark("four_process_trace", {"traceId": trace, "processIds": {node: self.pid(node) for node in names}, "lines": trace_logs})

        seen = set()
        def instances():
            value = aggregate()
            if aggregate_is_ready(value):
                seen.add(value["body"]["product"]["instance"])
            return sorted(seen)

        observed = wait_for("发现两个商品实例", instances, lambda values: values == ["product-a", "product-b"])
        self.mark("two_product_instances", observed)
        before = {node: self.pid(node) for node in ("gateway", "order", "marketing", "product-a", "product-b")}
        campaign = "cloud-lab-refresh-" + self.run_id
        began = time.perf_counter()
        self.update(**{"lab.campaign": campaign})
        refreshed = wait_for("Nacos 热更新活动文案", aggregate,
                             lambda r: aggregate_is_ready(r) and any(item["name"] == campaign for item in r["body"]["activities"]))
        assert before == {node: self.pid(node) for node in before}
        self.mark("nacos_hot_refresh", {"name": campaign, "milliseconds": round((time.perf_counter() - began) * 1000, 2),
                                       "unchangedProcessIds": before, "activity": refreshed["body"]["activities"][0]})

        # 无效数量在订单服务校验失败，可以验证网关放行而不创建真实订单。
        invalid_order = {"requestId": "gateway-limit-probe", "productId": 1, "quantity": 0}
        assert request("gateway", "/api/orders", "POST", invalid_order)["status"] == 401
        def order_probe(_):
            return request("gateway", "/api/orders", "POST", invalid_order, BUYER)["status"]

        self.update(**{"lab.gateway.qps": "2"})
        with ThreadPoolExecutor(max_workers=12) as pool:
            limited = wait_for("网关动态限流", lambda: list(pool.map(order_probe, range(12))),
                               lambda rows: {400, 429}.issubset(set(rows)))
            self.update(**{"lab.gateway.qps": DEFAULTS["lab.gateway.qps"]})
            wait_for("网关恢复原始额度", lambda: list(pool.map(order_probe, range(12))), lambda rows: set(rows) == {400})
        self.mark("gateway_rule_refresh", limited)

        self.update(**{"lab.product.hot-qps": "1"})
        with ThreadPoolExecutor(max_workers=12) as pool:
            hot = wait_for("商品热点规则", lambda: list(pool.map(lambda _: request("product-a", "/internal/products/1"), range(12))),
                           lambda rows: {200, 429}.issubset({r["status"] for r in rows}))
            self.update(**{"lab.product.hot-qps": DEFAULTS["lab.product.hot-qps"]})
            wait_for("商品恢复原始热点额度", lambda: list(pool.map(lambda _: request("product-a", "/internal/products/1"), range(12))),
                     lambda rows: all(row["status"] == 200 for row in rows))
        self.mark("product_hotspot", [result["status"] for result in hot])

    def verify_benchmark(self):
        delay = "65"
        self.update(**{"lab.marketing.delay-ms": delay, "lab.product.delay-ms": delay})
        wait_for("商品模拟延迟生效", lambda: request("product-a", "/internal/products/1"),
                 lambda r: r["status"] == 200 and r["ms"] >= 60)
        wait_for("营销模拟延迟生效", lambda: request("marketing", "/internal/marketing/1/activities"),
                 lambda r: r["status"] == 200 and r["ms"] >= 60)
        for mode in ("serial", "parallel"):
            for _ in range(4):
                response = aggregate(mode)
                assert aggregate_is_ready(response), response

        rows = []
        for concurrency in (1, 8):
            stats = {}
            for mode in ("serial", "parallel"):
                began = time.perf_counter()
                with ThreadPoolExecutor(max_workers=concurrency) as pool:
                    results = list(pool.map(lambda _: aggregate(mode), range(32)))
                elapsed = time.perf_counter() - began
                assert all(aggregate_is_ready(result) for result in results), results
                samples = sorted(result["ms"] for result in results)
                stats[mode] = {"samples": len(samples), "p50Ms": round(samples[math.ceil(.5 * len(samples)) - 1], 2),
                               "p95Ms": round(samples[math.ceil(.95 * len(samples)) - 1], 2), "reqPerSecond": round(len(samples) / elapsed, 2),
                               "latenciesMs": [round(sample, 2) for sample in samples]}
            rows.append({"concurrency": concurrency, **stats})
        self.update(**{"lab.marketing.delay-ms": "0", "lab.product.delay-ms": "0"})
        self.mark("parallel_fanout_benchmark", {"configuredDelayMsPerService": int(delay), "rows": rows})
        assert rows[0]["parallel"]["p50Ms"] < rows[0]["serial"]["p50Ms"], rows

    def verify_degradation_and_breakers(self):
        self.update(**{"lab.marketing.degraded": "true"})
        degraded = wait_for("聚合局部营销降级", aggregate,
                            lambda r: r["status"] == 200 and r["body"]["marketingDegraded"] is True)
        assert degraded["body"]["product"]["id"] == 1 and degraded["body"]["activities"] == [] and degraded["body"]["coupons"] == [], degraded
        self.mark("marketing_partial_fallback", degraded["body"])
        self.update(**{"lab.marketing.degraded": "false"})
        wait_for("营销局部降级恢复", aggregate, aggregate_is_ready)

        def circuits():
            result = request("order", "/lab/circuit")
            assert result["status"] == 200, result
            return result["body"]

        self.update(**{"lab.marketing.delay-ms": "260"})
        wait_for("营销慢调用配置生效", lambda: request("marketing", "/internal/marketing/1/activities"),
                 lambda r: r["status"] == 200 and r["ms"] >= 245)
        def open_circuit():
            aggregate()
            return circuits()
        opened = wait_for("Sentinel 慢调用熔断", open_circuit,
                          lambda rows: any(rows[resource]["slow"] == "OPEN" for resource in ("activities", "coupons")), seconds=18)
        blocked = aggregate()
        assert blocked["status"] == 200 and blocked["body"]["marketingDegraded"] is True, blocked
        self.update(**{"lab.marketing.delay-ms": "75"})
        wait_for("营销恢复到正常延迟", lambda: request("marketing", "/internal/marketing/1/activities"),
                 lambda r: r["status"] == 200 and 65 <= r["ms"] < 245)
        recovered = wait_for("允许半开探测后恢复", aggregate, aggregate_is_ready, seconds=20)
        closed = circuits()
        assert all(closed[resource]["slow"] == "CLOSED" for resource in ("activities", "coupons")), closed
        half_opens = {resource: closed[resource]["slowHalfOpens"] - opened[resource]["slowHalfOpens"]
                      for resource in ("activities", "coupons")}
        assert any(count > 0 for count in half_opens.values()), (opened, closed)
        self.update(**{"lab.marketing.delay-ms": "0"})
        self.mark("sentinel_slow_half_open", {"opened": opened, "halfOpenTransitions": half_opens, "closed": closed,
                                             "blockedMs": round(blocked["ms"], 2), "recoveredMs": round(recovered["ms"], 2)})

    def verify_inventory(self):
        def stock():
            return int(self.sql("SELECT stock FROM stock_product WHERE id=1")[0][0])

        def task(order_id):
            rows = self.sql(f"SELECT status FROM stock_lock_task WHERE order_id={order_id}")
            return rows[0][0] if rows else None

        initial = stock()
        assert initial == 50, initial
        rollback_key = f"rollback-{self.run_id}"
        rejected = request("gateway", "/api/orders", "POST",
                           {"requestId": rollback_key, "productId": 1, "quantity": 3, "couponId": 999999}, BUYER)
        assert rejected["status"] in (404, 409), rejected
        rows = self.sql("SELECT order_id, status FROM stock_lock_task WHERE product_id=1 ORDER BY order_id DESC LIMIT 1")
        assert len(rows) == 1 and rows[0][1] == "LOCKED" and stock() == initial - 3, rows
        rollback_id = int(rows[0][0])
        assert self.sql(f"SELECT COUNT(*) FROM lab_order WHERE id={rollback_id}") == [["0"]]
        wait_for("RabbitMQ 延时反查回补未落库订单", lambda: (task(rollback_id), stock()),
                 lambda state: state == ("RELEASED", initial), seconds=28)
        marker = f"stock_check orderId={rollback_id} source=mq orderState=MISSING"
        mq_logs = wait_for("MQ 消费回补日志", lambda: self.trace_lines("product-a", marker) + self.trace_lines("product-b", marker), bool)
        self.mark("rollback_inventory_reverse_check",
                  {"internalOrderId": rollback_id, "httpStatus": rejected["status"], "orderRows": 0,
                   "stockWhileLocked": initial - 3, "restoredStock": stock(), "task": task(rollback_id), "mqLogs": mq_logs})

        payload = {"requestId": f"timeout-{self.run_id}", "productId": 1, "quantity": 2, "couponId": self.coupon_id}
        created = request("gateway", "/api/orders", "POST", payload, BUYER)
        assert created["status"] == 201, created
        order_id = int(created["body"]["id"])
        retried = request("gateway", "/api/orders", "POST", payload, BUYER)
        assert retried["status"] == 200 and retried["body"]["id"] == order_id, retried
        assert stock() == initial - 2, stock()
        path = f"/api/orders/{order_id}"
        ended = wait_for("正常未支付订单超时关单", lambda: request("gateway", path, headers=BUYER),
                         lambda r: r["status"] == 200 and r["body"]["status"] == "CLOSED", seconds=25)
        wait_for("关单正向回补库存", lambda: (task(order_id), stock()), lambda state: state == ("RELEASED", initial), seconds=15)
        coupon = wait_for("关单正向释放优惠券", lambda: self.sql(f"SELECT status FROM marketing_coupon_task WHERE order_id={order_id}"),
                          lambda rows: rows == [["RELEASED"]], seconds=15)
        self.mark("order_timeout_forward_compensation", {"orderId": order_id, "orderStatus": ended["body"]["status"],
                                                         "task": task(order_id), "restoredStock": stock(), "coupon": coupon[0][0]})

    def run(self):
        if any(port_open("127.0.0.1", port) for port in PORTS.values()):
            raise RuntimeError("验收端口已被占用。请先确认对应进程，本脚本不会操作其进程。")
        self.directory.mkdir(parents=True, exist_ok=False)
        started = False
        try:
            self.command("Start", "-Drill")
            started = True
            self.connection = json.loads((self.directory / "connection.json").read_text(encoding="utf-8"))
            self.verify_topology_and_config()
            self.verify_benchmark()
            self.verify_degradation_and_breakers()
            self.verify_inventory()
            self.report["result"] = "PASS"
        except BaseException as error:
            self.report["result"] = "FAIL"
            self.report["failure"] = f"{type(error).__name__}: {error}"
            traceback.print_exc()
            raise
        finally:
            try:
                self.command("Stop", "-CleanData")
                assert not list(self.directory.glob("*.process.json"))
                if started:
                    self_hosts = {("127.0.0.1", port) for port in PORTS.values()}
                    self_hosts.add((self.connection["redisHost"], PORTS["redis"]))
                    wait_for("实验进程释放端口", lambda: [(host, port) for host, port in self_hosts if port_open(host, port)],
                             lambda ports: not ports, seconds=8)
                self.mark("cleanup", True)
            except BaseException as error:
                self.report["result"] = "FAIL"
                self.report["cleanupFailure"] = f"{type(error).__name__}: {error}"
                raise
            finally:
                self.save()
                print(f"验收证据 {self.directory / 'verification.json'}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", default=f"verify-{uuid4().hex[:12]}")
    arguments = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9-]{1,36}", arguments.run_id):
        parser.error("--run-id 应为 1～36 个小写字母、数字或短横线")
    Verification(arguments.run_id).run()
