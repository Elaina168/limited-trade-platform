"""验证隔离 Compose 项目：五服务健康、全链路交易/异步秒杀/发货收货、交易助手SSE与跨重建持久化、版本暴露与Redis故障演练。"""

import argparse
import http.cookiejar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def sse_frame(delta, finish=None):
    chunk = {
        "id": "compose-stream",
        "object": "chat.completion.chunk",
        "created": 1,
        "model": "deepseek-chat",
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }
    return "data: " + json.dumps(chunk, ensure_ascii=False) + "\n\n"


def start_mock_model():
    state = {"order_id": None}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            return

        def do_POST(self):
            if not self.path.endswith("/chat/completions"):
                self.send_response(404)
                self.end_headers()
                return
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            messages = payload.get("messages", [])
            has_tool_result = any(m.get("role") == "tool" for m in messages)
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.end_headers()
            if not has_tool_result and state["order_id"] is not None:
                tool_call = {
                    "index": 0,
                    "id": "compose-order-tool",
                    "type": "function",
                    "function": {
                        "name": "get_my_order",
                        "arguments": json.dumps({"orderId": state["order_id"]}),
                    },
                }
                body = (
                    sse_frame({"role": "assistant", "tool_calls": [tool_call]}, "tool_calls")
                    + 'data: {"id":"compose-stream","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}\n\n'
                    + "data: [DONE]\n\n"
                )
                self.wfile.write(body.encode("utf-8"))
                self.wfile.flush()
                return

            answer = f"Compose验收确认：订单 #{state['order_id']} 已完成收货。"
            body = (
                sse_frame({"role": "assistant", "content": answer})
                + sse_frame({"content": ""}, "stop")
                + 'data: {"id":"compose-stream","choices":[],"usage":{"prompt_tokens":18,"completion_tokens":10,"total_tokens":28}}\n\n'
                + "data: [DONE]\n\n"
            )
            self.wfile.write(body.encode("utf-8"))
            self.wfile.flush()

    server = ThreadingHTTPServer(("0.0.0.0", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, state


def wait_for(read, predicate, label, seconds=60):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        last = read()
        if predicate(last):
            return last
        time.sleep(0.5)
    raise AssertionError(f"{label}未在 {seconds} 秒内到达预期状态，最后结果: {last}")


def make_client(base_url):
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cookies))

    def request(path, status, method="GET", data=None, headers=None):
        req = urllib.request.Request(base_url + path, method=method, data=data, headers=headers or {})
        try:
            response = opener.open(req, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            if response.status != status:
                body_preview = response.read().decode("utf-8", errors="replace")[:300]
                raise AssertionError(f"{method} {path} 实际 {response.status}，预期 {status}：{body_preview}")
            return response.headers, response.read()

    def write(path, status, payload=None, form=False):
        _, body = request("/api/auth/csrf", 200)
        token = json.loads(body)
        headers = {token["headerName"]: token["token"]}
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
            data = (urllib.parse.urlencode(payload) if form else json.dumps(payload)).encode("utf-8")
        _, body = request(path, status, "POST", data, headers)
        return json.loads(body) if status in (200, 201, 202) and body else None

    return cookies, request, write


def verify(project):
    root = Path(__file__).resolve().parent.parent
    config = root / "config" / "container" / project
    env_file = config / ".env.docker"
    if not env_file.is_file():
        raise SystemExit("请先为该项目生成独立配置")

    git_sha = subprocess.run(
        ["git", "-c", "safe.directory=*", "rev-parse", "--short=12", "HEAD"],
        cwd=root, capture_output=True, text=True, check=False,
    ).stdout.strip() or "compose-local"

    model_server, model_state = start_mock_model()
    model_port = model_server.server_address[1]

    # 显式选用本轮配置，避免父进程的同名变量覆盖环境文件。
    env = os.environ.copy()
    for key in ("MYSQL_ROOT_PASSWORD", "MYSQL_PASSWORD", "RABBITMQ_DEFAULT_PASS"):
        env.pop(key, None)
    env["CONTAINER_CONFIG_DIR"] = str(config)
    env["APP_GIT_SHA"] = git_sha
    env["APP_RELEASE_LABEL"] = f"compose-{project}"
    env["SECKILL_ENABLED"] = "true"
    env["AGENT_ENABLED"] = "true"
    env["DEEPSEEK_API_KEY"] = "compose-local-mock"
    env["APP_SPRING_JSON"] = json.dumps({
        "app": {
            "agent": {
                "base-url": f"http://host.docker.internal:{model_port}",
                "model-timeout": "15s",
                "budget": {"monthly-yuan": 1},
            }
        }
    })
    prefix = ["docker", "compose", "--env-file", str(env_file), "-p", project]

    def compose(*args, capture=False, check=True):
        return subprocess.run([*prefix, *args], cwd=root, env=env, check=check,
                              text=True, encoding="utf-8", timeout=420,
                              stdout=subprocess.PIPE if capture else None).stdout

    def sql(statement):
        return compose(
            "exec", "-T", "mysql", "sh", "-c",
            'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -u "$MYSQL_USER" '
            '-D "$MYSQL_DATABASE" --batch --skip-column-names -e "$1"',
            "mysql", statement, capture=True,
        ).strip()

    def start_and_wait(*services):
        compose("start", *services)
        for svc in services:
            cid = compose("ps", "--quiet", svc, capture=True).strip()
            assert cid, f"未找到服务容器: {svc}"
            wait_for(
                lambda c=cid: subprocess.run(
                    ["docker", "inspect", "--format", "{{.State.Health.Status}}", c],
                    capture_output=True, text=True, check=True, timeout=10,
                ).stdout.strip(),
                lambda state: state == "healthy",
                f"{svc} 容器健康检查",
                seconds=120,
            )

    compose("config", "--quiet")
    existing_volumes = subprocess.run(
        ["docker", "volume", "ls", "--quiet", "--filter", f"label=com.docker.compose.project={project}"],
        check=True, capture_output=True, text=True, timeout=15,
    ).stdout.strip()
    if existing_volumes or compose("ps", "--all", "--quiet", capture=True).strip():
        model_server.shutdown()
        raise SystemExit("目标项目已有容器或数据卷，请为本次验收选择新项目名")

    report = {"project": project, "gitSha": git_sha}
    try:
        compose("up", "--build", "-d", "--wait", "--wait-timeout", "240")
        address = compose("port", "web", "80", capture=True).strip()
        if not re.fullmatch(r"127\.0\.0\.1:[0-9]+", address):
            raise AssertionError("网关发布地址不符合独立回环端口配置")
        base_url = "http://" + address
        buyer_cookies, buyer_req, buyer_write = make_client(base_url)
        admin_cookies, admin_req, admin_write = make_client(base_url)

        compose("ps")
        assert json.loads(buyer_req("/nginx-health", 200)[1]) == {"status": "UP"}
        assert json.loads(buyer_req("/api/ping", 200)[1]) == {"message": "pong"}
        headers, document = buyer_req("/orders", 200)
        assert headers["Cache-Control"] == "no-cache" and b'id="app"' in document
        headers, catalog = buyer_req("/api/products", 200)
        assert headers["Cache-Control"] == "no-store"
        assert any(product["id"] == 1 for product in json.loads(catalog))
        assert compose("exec", "-T", "redis", "redis-cli", "EXISTS",
                       "limited-trade:product:on-sale:v1", capture=True).strip() == "1"

        marker = uuid.uuid4().hex[:12]
        buyer_login = {"username": "buyer_" + marker, "password": "Compose_" + uuid.uuid4().hex}
        admin_login = {"username": "admin_" + marker, "password": "Compose_" + uuid.uuid4().hex}
        buyer_account = buyer_write("/api/auth/register", 201, buyer_login)
        admin_account = admin_write("/api/auth/register", 201, admin_login)
        user_id = int(buyer_account["id"])
        admin_id = int(admin_account["id"])
        sql(f"UPDATE app_user SET role='ADMIN' WHERE id={admin_id}")

        buyer_write("/api/auth/login", 204, buyer_login, form=True)
        admin_write("/api/auth/login", 204, admin_login, form=True)
        assert json.loads(buyer_req("/api/auth/me", 200)[1])["id"] == user_id
        assert json.loads(admin_req("/api/auth/me", 200)[1])["role"] == "ADMIN"

        # 核对发布版本快照与迁移完整性
        release_info = json.loads(admin_req("/api/admin/operations/release", 200)[1])
        assert release_info["application"]["gitSha"] == git_sha
        assert release_info["database"]["compatible"] is True
        assert release_info["database"]["latestDetectedMigration"] == "018_product_search_sync"
        report["releaseSnapshot"] = release_info

        address_row = buyer_write("/api/addresses", 201, {
            "recipientName": "容器买家",
            "phone": "13800138000",
            "province": "浙江省",
            "city": "杭州市",
            "district": "西湖区",
            "detailAddress": "容器持久路1号",
            "isDefault": True,
        })
        address_id = int(address_row["id"])

        # 1) 普通幂等订单 + RabbitMQ Outbox 确认（保留用于后续取消演练）
        order_input = {"productId": 1, "quantity": 1, "addressId": address_id, "requestId": "ord_" + marker}
        initial_stock = int(sql("SELECT stock FROM product WHERE id=1"))
        order = buyer_write("/api/orders", 201, order_input)
        order_id = int(order["id"])
        order_path = f"/api/orders/{order_id}"
        assert order["status"] == "PENDING_PAYMENT"
        assert buyer_write("/api/orders", 200, order_input)["id"] == order_id
        assert int(sql("SELECT stock FROM product WHERE id=1")) == initial_stock - 1
        wait_for(lambda: sql(f"SELECT status FROM order_timeout_outbox WHERE order_id={order_id}"),
                 lambda s: s == "SENT", "RabbitMQ 投递确认")

        # 2) 异步活动秒杀 -> 模拟支付 -> 管理员发货 -> 买家确认收货
        active_activity_id = int(sql(
            "SELECT id FROM activity WHERE starts_at<=NOW() AND ends_at>NOW() AND remaining_quantity>=1 ORDER BY id ASC LIMIT 1"
        ))
        act_product_id = int(sql(f"SELECT product_id FROM activity WHERE id={active_activity_id}"))
        seckill_req_id = "sk_" + marker
        queued = buyer_write(f"/api/activities/{active_activity_id}/seckill", 202, {
            "productId": act_product_id,
            "quantity": 1,
            "addressId": address_id,
            "requestId": seckill_req_id,
        })
        assert queued["status"] == "QUEUED"
        seckill_res = wait_for(
            lambda: json.loads(buyer_req(f"/api/activities/seckill/result/{seckill_req_id}", 200)[1]),
            lambda body: body["status"] == "CREATED",
            "异步秒杀落库",
        )
        completed_order_id = int(seckill_res["order"]["id"])
        assert buyer_write(f"/api/orders/{completed_order_id}/pay", 200)["status"] == "PAID"
        shipped = admin_write(f"/api/admin/orders/{completed_order_id}/ship", 200, {
            "carrier": "顺丰速运",
            "trackingNo": "SF-COMPOSE-" + marker,
        })
        assert shipped["status"] == "SHIPPED"
        assert buyer_write(f"/api/orders/{completed_order_id}/receive", 200)["status"] == "COMPLETED"

        # 3) 交易助手 SSE 流式查单与历史入库
        model_state["order_id"] = completed_order_id
        conv = buyer_write("/api/agent/conversations", 201)
        conv_id = conv["conversationId"]
        _, csrf_raw = buyer_req("/api/auth/csrf", 200)
        csrf_obj = json.loads(csrf_raw)
        sse_headers, sse_bytes = buyer_req(
            f"/api/agent/conversations/{conv_id}/messages/stream",
            200,
            method="POST",
            data=json.dumps({"message": f"请查询订单 #{completed_order_id} 的状态"}).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
                csrf_obj["headerName"]: csrf_obj["token"],
            },
        )
        sse_text = sse_bytes.decode("utf-8")
        assert "Compose验收确认" in sse_text
        history = json.loads(buyer_req(f"/api/agent/conversations/{conv_id}/messages", 200)[1])
        assert len(history["turns"]) == 1
        assert history["turns"][0]["orders"][0]["id"] == completed_order_id
        print("通过：五服务健康、幂等下单、异步秒杀、支付发货收货闭环及交易助手SSE问答", flush=True)

        # 4) 启停与 down/up 全容器重建持久化验证
        compose("stop", "web", "app")
        start_and_wait("app", "web")
        buyer_cookies.clear()
        buyer_write("/api/auth/login", 204, buyer_login, form=True)
        assert json.loads(buyer_req(order_path, 200)[1])["requestId"] == "ord_" + marker

        app_id = compose("ps", "--quiet", "app", capture=True).strip()
        mysql_id = compose("ps", "--quiet", "mysql", capture=True).strip()
        compose("down", "--timeout", "20")
        compose("up", "-d", "--wait", "--wait-timeout", "240")
        assert compose("port", "web", "80", capture=True).strip() == address
        assert compose("ps", "--quiet", "app", capture=True).strip() != app_id
        assert compose("ps", "--quiet", "mysql", capture=True).strip() != mysql_id
        buyer_cookies.clear()
        buyer_write("/api/auth/login", 204, buyer_login, form=True)
        assert json.loads(buyer_req(order_path, 200)[1])["requestId"] == "ord_" + marker
        assert json.loads(buyer_req(f"/api/orders/{completed_order_id}", 200)[1])["status"] == "COMPLETED"
        assert int(sql("SELECT stock FROM product WHERE id=1")) == initial_stock - 1
        persisted_history = json.loads(buyer_req(f"/api/agent/conversations/{conv_id}/messages", 200)[1])
        assert len(persisted_history["turns"]) == 1
        assert "Compose验收确认" in persisted_history["turns"][0]["answer"]
        print("通过：启停与重建后账号、订单、库存及交易助手会话历史均完整保留", flush=True)

        # 5) Redis 故障与恢复演练
        compose("stop", "redis")
        buyer_req("/api/ping", 200)
        denied_input = {**order_input, "requestId": "denied_" + marker}
        buyer_write("/api/orders", 503, denied_input)
        assert int(sql("SELECT stock FROM product WHERE id=1")) == initial_stock - 1
        start_and_wait("redis")
        retry = buyer_write("/api/orders", 200, order_input)
        assert retry["id"] == order_id
        assert buyer_write(order_path + "/cancel", 200)["status"] == "CANCELED"
        assert int(sql("SELECT stock FROM product WHERE id=1")) == initial_stock
        print("通过：Redis 故障时应用探针存活、下单拒绝；恢复后原单可查和取消", flush=True)

        out_dir = root / "tools" / "compose-verification" / project
        out_dir.mkdir(parents=True, exist_ok=True)
        report.update({
            "completedOrderId": completed_order_id,
            "pendingCanceledOrderId": order_id,
            "conversationId": conv_id,
            "persistedAcrossRecreate": True,
            "redisFailureDrillPassed": True,
        })
        (out_dir / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (AssertionError, OSError, ValueError, subprocess.SubprocessError):
        compose("ps", "--all", check=False)
        compose("logs", "--tail", "20", "web", "app", "mysql", "redis", "rabbitmq", check=False)
        raise
    finally:
        model_server.shutdown()
        compose("down", "--timeout", "20", check=False)
        print(f"验收容器和网络已移除，保留 {project} 的命名卷", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", help="使用全新的 trade-check- 开头的项目名")
    args = parser.parse_args()
    if not re.fullmatch(r"trade-check-[a-z0-9][a-z0-9_-]{0,35}", args.project):
        parser.error("项目名必须以 trade-check- 开头，且总长最多 48")
    verify(args.project)
