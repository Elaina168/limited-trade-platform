"""验证隔离 Compose 项目。测试结束移除容器与网络，保留命名卷及外置配置。"""

import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def wait_for(read, expected, label, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if read() == expected:
            return
        time.sleep(1)
    raise AssertionError(f"{label}未在 {seconds} 秒内到达预期状态")


def verify(project):
    root = Path(__file__).resolve().parent.parent
    config = root / "config" / "container" / project
    env_file = config / ".env.docker"
    if not env_file.is_file():
        raise SystemExit("请先为该项目生成独立配置")

    # 显式选用本轮配置，避免父进程的同名变量覆盖环境文件。
    env = os.environ.copy()
    for key in ("MYSQL_ROOT_PASSWORD", "MYSQL_PASSWORD", "RABBITMQ_DEFAULT_PASS"):
        env.pop(key, None)
    env["CONTAINER_CONFIG_DIR"] = str(config)
    prefix = ["docker", "compose", "--env-file", str(env_file), "-p", project]

    def compose(*args, capture=False, check=True):
        return subprocess.run([*prefix, *args], cwd=root, env=env, check=check,
                              text=True, encoding="utf-8", timeout=420,
                              stdout=subprocess.PIPE if capture else None).stdout

    def sql(statement):
        # 密码由容器自身的环境提供，控制台和宿主机命令行只包含查询本身。
        return compose(
            "exec", "-T", "mysql", "sh", "-c",
            'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -u "$MYSQL_USER" '
            '-D "$MYSQL_DATABASE" --batch --skip-column-names -e "$1"',
            "mysql", statement, capture=True,
        ).strip()

    compose("config", "--quiet")
    existing_volumes = subprocess.run(
        ["docker", "volume", "ls", "--quiet", "--filter", f"label=com.docker.compose.project={project}"],
        check=True, capture_output=True, text=True, timeout=15,
    ).stdout.strip()
    if existing_volumes or compose("ps", "--all", "--quiet", capture=True).strip():
        raise SystemExit("目标项目已有容器或数据卷，请为本次验收选择新项目名")

    try:
        compose("up", "--build", "-d", "--wait", "--wait-timeout", "240")
        address = compose("port", "web", "80", capture=True).strip()
        if not re.fullmatch(r"127\.0\.0\.1:[0-9]+", address):
            raise AssertionError("网关发布地址不符合独立回环端口配置")
        base_url = "http://" + address
        cookies = http.cookiejar.CookieJar()
        client = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cookies))

        def request(path, status, method="GET", data=None, headers=None):
            req = urllib.request.Request(base_url + path, method=method, data=data, headers=headers or {})
            try:
                response = client.open(req, timeout=15)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                if response.status != status:
                    raise AssertionError(f"{method} {path} 实际 {response.status}，预期 {status}")
                return response.headers, response.read()

        def write(path, status, payload=None, form=False):
            _, body = request("/api/auth/csrf", 200)
            token = json.loads(body)
            headers = {token["headerName"]: token["token"]}
            data = None
            if payload is not None:
                headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
                data = (urllib.parse.urlencode(payload) if form else json.dumps(payload)).encode()
            _, body = request(path, status, "POST", data, headers)
            return json.loads(body) if status in (200, 201) else None

        compose("ps")
        assert json.loads(request("/nginx-health", 200)[1]) == {"status": "UP"}
        assert json.loads(request("/api/ping", 200)[1]) == {"message": "pong"}
        headers, document = request("/orders", 200)
        assert headers["Cache-Control"] == "no-cache" and b'id="app"' in document
        headers, catalog = request("/api/products", 200)
        assert headers["Cache-Control"] == "no-store"
        assert any(product["id"] == 1 for product in json.loads(catalog))
        assert compose("exec", "-T", "redis", "redis-cli", "EXISTS",
                       "limited-trade:product:on-sale:v1", capture=True).strip() == "1"

        marker = uuid.uuid4().hex
        login = {"username": "compose_" + marker, "password": "Compose_" + uuid.uuid4().hex}
        account = write("/api/auth/register", 201, login)
        user_id = int(account["id"])
        write("/api/auth/login", 204, login, form=True)
        assert json.loads(request("/api/auth/me", 200)[1])["id"] == user_id

        order_input = {"productId": 1, "quantity": 1, "requestId": marker}
        initial_stock = int(sql("SELECT stock FROM product WHERE id=1"))
        order = write("/api/orders", 201, order_input)
        order_id = int(order["id"])
        order_path = f"/api/orders/{order_id}"
        assert order["status"] == "PENDING_PAYMENT"
        assert write("/api/orders", 200, order_input)["id"] == order_id
        assert int(sql("SELECT stock FROM product WHERE id=1")) == initial_stock - 1
        wait_for(lambda: sql(f"SELECT status FROM order_timeout_outbox WHERE order_id={order_id}"),
                 "SENT", "RabbitMQ 投递确认")
        print("通过：Vue、Java、MySQL、Redis 缓存、幂等下单及 RabbitMQ 投递确认", flush=True)

        compose("stop", "web", "app")
        compose("start", "--wait", "--wait-timeout", "120", "app", "web")
        cookies.clear()
        write("/api/auth/login", 204, login, form=True)
        assert json.loads(request(order_path, 200)[1])["requestId"] == marker

        app_id = compose("ps", "--quiet", "app", capture=True).strip()
        mysql_id = compose("ps", "--quiet", "mysql", capture=True).strip()
        compose("down", "--timeout", "20")
        compose("up", "--build", "-d", "--wait", "--wait-timeout", "240")
        assert compose("port", "web", "80", capture=True).strip() == address
        assert compose("ps", "--quiet", "app", capture=True).strip() != app_id
        assert compose("ps", "--quiet", "mysql", capture=True).strip() != mysql_id
        cookies.clear()
        write("/api/auth/login", 204, login, form=True)
        assert json.loads(request(order_path, 200)[1])["requestId"] == marker
        assert int(sql("SELECT stock FROM product WHERE id=1")) == initial_stock - 1
        print("通过：启停与重建后账号、订单、库存均保留", flush=True)

        compose("stop", "redis")
        request("/api/ping", 200)
        denied_input = {**order_input, "requestId": uuid.uuid4().hex}
        write("/api/orders", 503, denied_input)
        assert sql(f"SELECT COUNT(*) FROM trade_order WHERE user_id={user_id}") == "1"
        assert int(sql("SELECT stock FROM product WHERE id=1")) == initial_stock - 1
        compose("start", "--wait", "--wait-timeout", "120", "redis")
        retry = write("/api/orders", 200, order_input)
        assert retry["id"] == order_id
        assert write(order_path + "/cancel", 200)["status"] == "CANCELED"
        assert int(sql("SELECT stock FROM product WHERE id=1")) == initial_stock
        print("通过：Redis 故障时应用探针存活、下单拒绝；恢复后原单可查和取消", flush=True)
    except (AssertionError, OSError, ValueError, subprocess.SubprocessError):
        compose("ps", "--all", check=False)
        compose("logs", "--tail", "15", "web", "app", "mysql", "redis", "rabbitmq", check=False)
        raise
    finally:
        compose("down", "--timeout", "20")
        print(f"验收容器和网络已移除，保留 {project} 的命名卷", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", help="使用全新的 trade-check- 开头的项目名")
    args = parser.parse_args()
    if not re.fullmatch(r"trade-check-[a-z0-9][a-z0-9_-]{0,35}", args.project):
        parser.error("项目名必须以 trade-check- 开头，且总长最多 48")
    verify(args.project)
