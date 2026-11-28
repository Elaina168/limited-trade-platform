"""阶段 7.2 / 补强 15：隔离真实环境发布升级、不兼容版本拦截回滚、MQ积压告警触发与恢复清零演练。"""

import argparse
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parent.parent
MYSQLD = os.environ.get("ACCEPTANCE_MYSQLD", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe")
MYSQL = os.environ.get("ACCEPTANCE_MYSQL", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
JAVA = os.environ.get("ACCEPTANCE_JAVA", r"C:\Program Files\Java\jdk-21.0.10\bin\java.exe")

MYSQL_PORT = int(os.environ.get("DRILL_MYSQL_PORT", "23325"))
REDIS_PORT = int(os.environ.get("DRILL_REDIS_PORT", "16395"))
MQ_PORT = int(os.environ.get("DRILL_MQ_PORT", "5695"))
MQ_DIST_PORT = int(os.environ.get("DRILL_MQ_DIST_PORT", "25695"))
JAVA_PORT = int(os.environ.get("DRILL_JAVA_PORT", "28095"))
SCHEMA = "limited_release_drill"


def wait_until(label, fn, timeout=60.0, interval=0.3):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = fn()
            if last:
                return last
        except Exception as err:
            last = err
        time.sleep(interval)
    raise RuntimeError(f"{label} 超时，最后状态: {last}")


def redis_ping(host, port):
    with socket.create_connection((host, port), timeout=2.0) as sock:
        sock.sendall(b"*1\r\n$4\r\nPING\r\n")
        return b"+PONG" in sock.recv(64)


def kill_proc(proc):
    if proc is None or proc.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill.exe", "/PID", str(proc.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    else:
        proc.kill()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass


def make_http_session(base_url):
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar))

    def req(path, expected_status=200, method="GET", data=None, headers=None):
        r = urllib.request.Request(base_url + path, data=data, method=method, headers=headers or {})
        try:
            resp = opener.open(r, timeout=10)
        except urllib.error.HTTPError as e:
            resp = e
        with resp:
            raw = resp.read().decode("utf-8", errors="replace")
            if expected_status is not None and resp.status != expected_status:
                raise AssertionError(f"{method} {path} 返回 {resp.status} (预期 {expected_status}): {raw[:260]}")
            return resp.status, (json.loads(raw) if raw and raw.lstrip().startswith(("{", "[")) else raw)

    def write(path, expected_status, payload=None, form=False):
        _, csrf = req("/api/auth/csrf", 200)
        headers = {csrf["headerName"]: csrf["token"]}
        body = None
        if payload is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
            body = (urllib.parse.urlencode(payload) if form else json.dumps(payload)).encode("utf-8")
        _, parsed = req(path, expected_status, method="POST", data=body, headers=headers)
        return parsed

    return jar, req, write


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path, help="基线发布 JAR")
    parser.add_argument("upgrade", type=Path, help="升级后 JAR")
    parser.add_argument("incompatible", type=Path, help="需要未应用迁移的 JAR")
    args = parser.parse_args()
    artifacts = {}
    for role, path in vars(args).items():
        path = path.resolve(strict=True)
        with zipfile.ZipFile(path) as jar:
            properties = dict(line.split("=", 1) for line in
                              jar.read("META-INF/build-info.properties").decode("utf-8").splitlines())
        with path.open("rb") as artifact:
            digest = hashlib.file_digest(artifact, "sha256").hexdigest()
        artifacts[role] = {"path": path, "sha256": digest, "gitSha": properties["build.git.commit"],
                           "label": properties["build.releaseLabel"],
                           "requiredMigration": properties["build.requiredMigration"]}
    if (len({item["sha256"] for item in artifacts.values()}) != 3
            or len({item["label"] for item in artifacts.values()}) != 3
            or any(len(item["gitSha"]) != 40 for item in artifacts.values())):
        parser.error("需要三个分别构建、标签不同且内嵌完整 Git SHA 的 JAR")

    run_id = secrets.token_hex(5)
    db_pwd = "DrillDb_" + secrets.token_hex(12)

    work_dir = Path(tempfile.mkdtemp(prefix="release-drill-", dir=str(ROOT / "target")))
    print(f"发布回滚与告警演练隔离目录: {work_dir}")

    redis_proc = None
    mq_proc = None
    mysql_proc = None
    java_proc = None
    mq_env = None

    try:
        # 1. 启动隔离 WSL2 Redis
        wsl_ip_out = subprocess.run(
            ["wsl.exe", "-d", "Ubuntu-22.04", "--", "hostname", "-I"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8", errors="replace", check=True,
        ).stdout.strip()
        redis_host = wsl_ip_out.split()[0] if wsl_ip_out else "127.0.0.1"
        redis_log = open(work_dir / "redis.log", "wb")
        redis_proc = subprocess.Popen(
            ["wsl.exe", "-d", "Ubuntu-22.04", "--", "redis-server",
             "--bind", "0.0.0.0", "--protected-mode", "no", "--port", str(REDIS_PORT),
             "--dir", "/tmp", "--save", "", "--appendonly", "no"],
            stdin=subprocess.PIPE, stdout=redis_log, stderr=redis_log,
        )
        redis_log.close()
        wait_until("Redis", lambda: redis_ping(redis_host, REDIS_PORT), 25.0)

        # 2. 启动隔离便携版 RabbitMQ
        mq_base = work_dir / "rabbitmq-base"
        mq_base.mkdir(parents=True, exist_ok=True)
        (mq_base / "rabbitmq.conf").write_text(
            f"listeners.tcp.local = 127.0.0.1:{MQ_PORT}\n"
            f"distribution.listener.port_range.min = {MQ_DIST_PORT}\n"
            f"distribution.listener.port_range.max = {MQ_DIST_PORT}\n",
            encoding="utf-8",
        )
        (mq_base / "enabled_plugins").write_text("[].\n", encoding="utf-8")
        mq_env = {
            **os.environ,
            "ERLANG_HOME": str(ROOT / "tools" / "Erlang OTP"),
            "RABBITMQ_BASE": str(mq_base),
            "RABBITMQ_CONFIG_FILE": str(mq_base / "rabbitmq"),
            "RABBITMQ_ENABLED_PLUGINS_FILE": str(mq_base / "enabled_plugins"),
            "RABBITMQ_NODENAME": f"drill_{run_id}@localhost",
            "ERL_EPMD_ADDRESS": "127.0.0.1",
            "RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS": "+S 4:4 -kernel inet_dist_use_interface {127,0,0,1}",
        }
        mq_log = open(work_dir / "rabbitmq.log", "wb")
        mq_proc = subprocess.Popen(
            ["cmd.exe", "/c", str(ROOT / "tools" / "rabbitmq_server-4.3.6" / "sbin" / "rabbitmq-server.bat")],
            env=mq_env, stdin=subprocess.DEVNULL, stdout=mq_log, stderr=mq_log,
        )
        mq_log.close()
        wait_until("RabbitMQ", lambda: socket.create_connection(("127.0.0.1", MQ_PORT), 1.0).close() or True, 60.0)

        # 3. 启动隔离 MySQL 8.0 并执行 schema + 002..018 + seed
        mysql_data = work_dir / "mysql-data"
        (work_dir / "config").mkdir(exist_ok=True)
        (work_dir / "config" / "db.properties").write_text("", encoding="utf-8")
        (work_dir / "config" / "rabbitmq.properties").write_text("", encoding="utf-8")
        init_sql = work_dir / "init.sql"
        init_sql.write_text(
            f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{db_pwd}';\n"
            f"CREATE DATABASE {SCHEMA} CHARACTER SET utf8mb4;\n",
            encoding="utf-8",
        )
        subprocess.run([MYSQLD, "--no-defaults", "--initialize-insecure", f"--datadir={mysql_data}"],
                       cwd=work_dir, check=True, capture_output=True)
        mysql_log = open(work_dir / "mysql.log", "wb")
        mysql_proc = subprocess.Popen(
            [MYSQLD, "--no-defaults", f"--datadir={mysql_data}", "--bind-address=127.0.0.1",
             f"--port={MYSQL_PORT}", "--mysqlx=OFF", "--skip-log-bin", f"--init-file={init_sql}"],
            cwd=work_dir, stdin=subprocess.DEVNULL, stdout=mysql_log, stderr=mysql_log,
        )
        mysql_log.close()

        mysql_base_args = [
            MYSQL, "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", f"--port={MYSQL_PORT}",
            "--user=root", "--default-character-set=utf8mb4", "--batch", "--skip-column-names",
        ]

        def sql(query, db=SCHEMA):
            cmd = [*mysql_base_args, db] if db else mysql_base_args
            res = subprocess.run(cmd, input=query, encoding="utf-8", capture_output=True,
                                 env={**os.environ, "MYSQL_PWD": db_pwd}, check=True, timeout=30)
            return res.stdout.strip()

        wait_until("MySQL", lambda: sql("SELECT 1") == "1", 45.0)
        init_sql.unlink(missing_ok=True)

        sql_dir = ROOT / "sql"
        migrations = sorted(p.name for p in sql_dir.iterdir() if p.name[:3].isdigit() and p.suffix == ".sql")
        ddl = "\n".join((sql_dir / name).read_text(encoding="utf-8") for name in ["schema.sql", *migrations, "seed.sql"])
        sql(ddl)

        base_url = f"http://127.0.0.1:{JAVA_PORT}"

        def start_app(tag, role, extra_args=None):
            nonlocal java_proc
            log_path = work_dir / f"java-{tag}.log"
            app_log_file = work_dir / f"app-{tag}.json.log"
            fd = open(log_path, "wb")
            env = {
                **os.environ,
                "DB_URL": f"jdbc:mysql://127.0.0.1:{MYSQL_PORT}/{SCHEMA}?allowPublicKeyRetrieval=true&sslMode=DISABLED",
                "DB_USERNAME": "root",
                "DB_PASSWORD": db_pwd,
                "MQ_HOST": "127.0.0.1",
                "MQ_PORT": str(MQ_PORT),
                "MQ_USERNAME": "guest",
                "MQ_PASSWORD": "guest",
                "MQ_VIRTUAL_HOST": "/",
                "APP_RELEASE_GATE_TRAFFIC": "true",
                "APP_INSTANCE_ID": f"drill-{tag}",
                "APP_LOG_FILE": str(app_log_file),
            }
            args = [
                JAVA, "-Xms256m", "-Xmx512m", "-Duser.timezone=Asia/Shanghai",
                "-jar", str(artifacts[role]["path"]),
                "--server.address=127.0.0.1",
                f"--server.port={JAVA_PORT}",
                f"--spring.data.redis.host={redis_host}",
                f"--spring.data.redis.port={REDIS_PORT}",
                "--app.agent.enabled=false",
                "--app.agent.rag.enabled=false",
                f"--app.product-cache.key=drill:{run_id}:catalog",
                f"--app.order-rate-limit.key-prefix=drill:{run_id}:limit",
                "--app.order-rate-limit.max-requests=50",
                f"--app.order-message.name-prefix=drill-{run_id}",
                "--app.order-message.wait-ttl-ms=4000",
                f"--app.seckill.key-prefix=drill:{run_id}:sk",
                *(extra_args or []),
            ]
            java_proc = subprocess.Popen(args, cwd=work_dir, env=env, stdin=subprocess.DEVNULL, stdout=fd, stderr=fd)
            fd.close()
            _, ping_req, _ = make_http_session(base_url)
            wait_until(f"Java({tag})", lambda: ping_req("/api/ping", 200)[1]["message"] == "pong", 45.0)
            if role != "incompatible":
                wait_until(f"Java({tag}) 就绪", lambda: ping_req("/internal/ready", None)[0] == 200, 45.0)
            return app_log_file

        def check_release(snapshot, role):
            assert snapshot["application"]["gitSha"] == artifacts[role]["gitSha"]
            assert snapshot["application"]["releaseLabel"] == artifacts[role]["label"]
            assert snapshot["database"]["requiredMigration"] == artifacts[role]["requiredMigration"]

        # === 阶段 A：首次发布 r1 与发布前/后元数据核验 ===
        print("1) 发布基线 JAR 并核验版本与迁移快照...")
        start_app("r1", "baseline")
        buyer_jar, buyer_req, buyer_write = make_http_session(base_url)
        admin_jar, admin_req, admin_write = make_http_session(base_url)

        buyer_creds = {"username": "drill_buyer", "password": "BuyerPass_2026!"}
        admin_creds = {"username": "drill_admin", "password": "AdminPass_2026!"}
        buyer_write("/api/auth/register", 201, buyer_creds)
        adm = admin_write("/api/auth/register", 201, admin_creds)
        sql(f"UPDATE app_user SET role='ADMIN' WHERE id={int(adm['id'])}")
        buyer_write("/api/auth/login", 204, buyer_creds, form=True)
        admin_write("/api/auth/login", 204, admin_creds, form=True)

        _, r1_release = admin_req("/api/admin/operations/release", 200)
        raw_release_str = json.dumps(r1_release, ensure_ascii=False)
        assert db_pwd not in raw_release_str and "password" not in raw_release_str.lower()
        check_release(r1_release, "baseline")
        assert r1_release["database"]["compatible"] is True
        assert len(r1_release["database"]["appliedMigrations"]) == 18

        addr = buyer_write("/api/addresses", 201, {
            "recipientName": "演练买家", "phone": "13800138000",
            "province": "浙江省", "city": "杭州市", "district": "西湖区",
            "detailAddress": "发布演练路1号", "isDefault": True,
        })
        addr_id = int(addr["id"])
        initial_stock = int(sql("SELECT stock FROM product WHERE id=1"))
        order1 = buyer_write("/api/orders", 201, {"productId": 1, "quantity": 1, "addressId": addr_id, "requestId": "r1-order-1"})
        order1_id = int(order1["id"])
        wait_until("r1 Outbox SENT", lambda: sql(f"SELECT status FROM order_timeout_outbox WHERE order_id={order1_id}") == "SENT")

        # === 阶段 B：平滑升级 r1 -> r2 ===
        print("2) 启动升级 JAR 并验证业务与历史订单连续性...")
        kill_proc(java_proc)
        start_app("r2", "upgrade")
        buyer_jar.clear()
        admin_jar.clear()
        buyer_write("/api/auth/login", 204, buyer_creds, form=True)
        admin_write("/api/auth/login", 204, admin_creds, form=True)

        _, r2_release = admin_req("/api/admin/operations/release", 200)
        check_release(r2_release, "upgrade")
        assert r2_release["database"]["compatible"] is True
        assert buyer_write(f"/api/orders/{order1_id}/pay", 200)["status"] == "PAID"
        order2 = buyer_write("/api/orders", 201, {"productId": 1, "quantity": 1, "addressId": addr_id, "requestId": "r2-order-2"})
        order2_id = int(order2["id"])

        # === 阶段 C：部署不兼容版本 r3-bad 拦截并快速回滚至 r2 ===
        print("3) 模拟部署要求未执行迁移 019 的故障版本 r3-bad -> 触发 503 拦截 -> 执行回滚至 r2...")
        kill_proc(java_proc)
        r3_log_file = start_app("r3-bad", "incompatible")
        required = artifacts["incompatible"]["requiredMigration"]
        wait_until("不兼容产物已完成迁移校验", lambda: r3_log_file.is_file() and any(
            row.get("event") == "release_schema_incompatible"
            and row.get("gitSha") == artifacts["incompatible"]["gitSha"]
            and row.get("releaseLabel") == artifacts["incompatible"]["label"]
            and row.get("missingMigrations") == [required]
            for row in map(json.loads, r3_log_file.read_text(encoding="utf-8").splitlines())))
        r3_status, _ = admin_req("/internal/ready", 503)
        r3_business_status, _ = buyer_req("/api/products", 503)

        # 立即回滚回 r2
        kill_proc(java_proc)
        start_app("r2-rollback", "upgrade")
        buyer_jar.clear()
        admin_jar.clear()
        buyer_write("/api/auth/login", 204, buyer_creds, form=True)
        admin_write("/api/auth/login", 204, admin_creds, form=True)
        _, rb_release = admin_req("/api/admin/operations/release", 200)
        check_release(rb_release, "upgrade")
        assert rb_release["database"]["compatible"] is True
        assert buyer_req(f"/api/orders/{order1_id}", 200)[1]["status"] == "PAID"
        assert buyer_req(f"/api/orders/{order2_id}", 200)[1]["status"] == "PENDING_PAYMENT"
        assert int(sql("SELECT stock FROM product WHERE id=1")) == initial_stock - 2

        # === 阶段 D：消息积压与延迟关单告警触发、去重与消费恢复清零 ===
        print("4) 模拟消费者暂停导致关单队列积压与超时订单延迟 -> 验证告警触发去重 -> 恢复消费自动清零...")
        b1 = buyer_write("/api/orders", 201, {"productId": 1, "quantity": 1, "addressId": addr_id, "requestId": "backlog-1"})
        b2 = buyer_write("/api/orders", 201, {"productId": 1, "quantity": 1, "addressId": addr_id, "requestId": "backlog-2"})
        b1_id, b2_id = int(b1["id"]), int(b2["id"])
        wait_until("积压订单 Outbox SENT", lambda: sql(
            f"SELECT COUNT(*) FROM order_timeout_outbox WHERE order_id IN ({b1_id},{b2_id}) AND status='SENT'"
        ) == "2")

        kill_proc(java_proc)
        sql(f"UPDATE trade_order SET expires_at=DATE_SUB(NOW(), INTERVAL 5 SECOND) WHERE id IN ({b1_id},{b2_id})")
        alert_log_file = start_app(
            "r2-alert",
            "upgrade",
            extra_args=[
                "--app.order-message.enabled=false",
                "--app.order-timeout.enabled=false",
                "--app.order-message.monitor.interval-ms=1000",
                "--app.order-message.monitor.ready-messages=2",
                "--app.order-message.monitor.overdue-seconds=2",
            ],
        )
        admin_jar.clear()
        admin_write("/api/auth/login", 204, admin_creds, form=True)

        firing_snap_1 = wait_until(
            "READY_BACKLOG 与 ORDER_CLOSE_DELAYED 同时触发",
            lambda: (
                s := admin_req("/api/admin/operations/order-messages", 200)[1],
                s if {"READY_BACKLOG", "ORDER_CLOSE_DELAYED"}.issubset(set(s["alerts"])) else None,
            )[1],
            timeout=20.0,
            interval=0.5,
        )
        time.sleep(1.5)
        _, firing_snap_2 = admin_req("/api/admin/operations/order-messages", 200)
        assert firing_snap_1["alerts"] == firing_snap_2["alerts"], "重复采样告警标识应去重稳定，不随轮次膨胀"
        wait_until(
            "结构化 WARN 告警日志落盘",
            lambda: alert_log_file.is_file() and '"event":"order_message_health"' in alert_log_file.read_text(encoding="utf-8")
            and '"level":"WARN"' in alert_log_file.read_text(encoding="utf-8"),
            timeout=10.0,
        )

        # 恢复消费者运行，验证自动排空积压、精确回补库存且告警归零
        kill_proc(java_proc)
        recovery_log_file = start_app(
            "r2-recovered",
            "upgrade",
            extra_args=[
                "--app.order-message.enabled=true",
                "--app.order-message.monitor.interval-ms=1000",
                "--app.order-message.monitor.ready-messages=2",
                "--app.order-message.monitor.overdue-seconds=2",
            ],
        )
        admin_jar.clear()
        admin_write("/api/auth/login", 204, admin_creds, form=True)
        recovered_snap = wait_until(
            "消费者恢复后积压排空且告警清零",
            lambda: (
                s := admin_req("/api/admin/operations/order-messages", 200)[1],
                s if s["alerts"] == [] and s["queues"]["readyReady"] == 0 and s["database"]["overdueOrders"] == 0 else None,
            )[1],
            timeout=25.0,
            interval=0.5,
        )
        assert sql(f"SELECT status FROM trade_order WHERE id={b1_id}") == "CLOSED"
        assert sql(f"SELECT status FROM trade_order WHERE id={b2_id}") == "CLOSED"
        # order1(PAID) + order2(未到期PENDING_PAYMENT) 占用 2 件，b1/b2 已关单归还 2 件
        assert int(sql("SELECT stock FROM product WHERE id=1")) == initial_stock - 2
        wait_until(
            "结构化 INFO 恢复日志落盘",
            lambda: recovery_log_file.is_file() and '"alertCount":0' in recovery_log_file.read_text(encoding="utf-8"),
            timeout=10.0,
        )

        summary = {
            "measuredAt": datetime.now(timezone.utc).isoformat(),
            "runId": run_id,
            "artifacts": {role: {key: value for key, value in item.items() if key != "path"}
                          for role, item in artifacts.items()},
            "releaseUpgrade": {
                "fromLabel": r1_release["application"]["releaseLabel"],
                "toLabel": r2_release["application"]["releaseLabel"],
                "appliedMigrationsCount": len(r2_release["database"]["appliedMigrations"]),
                "latestMigration": r2_release["database"]["latestDetectedMigration"],
                "secretLeakCheckPassed": True,
            },
            "incompatibleReleaseAndRollback": {
                "rejectedLabel": artifacts["incompatible"]["label"],
                "readinessHttpStatus": r3_status,
                "businessHttpStatus": r3_business_status,
                "missingMigrations": [required],
                "rolledBackToLabel": rb_release["application"]["releaseLabel"],
                "ordersAndStockIntact": True,
            },
            "mqAlertAndRecovery": {
                "firingAlerts": firing_snap_1["alerts"],
                "deduplicatedAcrossSamples": firing_snap_1["alerts"] == firing_snap_2["alerts"],
                "firingReadyBacklog": firing_snap_1["queues"]["readyReady"],
                "firingOverdueOrders": firing_snap_1["database"]["overdueOrders"],
                "recoveredAlerts": recovered_snap["alerts"],
                "recoveredReadyBacklog": recovered_snap["queues"]["readyReady"],
                "closedBacklogOrders": [b1_id, b2_id],
                "finalStockConserved": True,
            },
        }
        out_dir = ROOT / "tools" / "release-drill" / run_id
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / "summary.json"
        out_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"=== 发布、回滚与告警恢复演练全部通过，证据已保存至 {out_path} ===")
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    finally:
        kill_proc(java_proc)
        if mysql_proc is not None and mysql_proc.poll() is None:
            subprocess.run(
                [MYSQL, "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", f"--port={MYSQL_PORT}", "--user=root", "SHUTDOWN"],
                input="SHUTDOWN;", encoding="utf-8", env={**os.environ, "MYSQL_PWD": db_pwd},
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False, timeout=5,
            )
            kill_proc(mysql_proc)
        if mq_proc is not None and mq_proc.poll() is None and mq_env is not None:
            subprocess.run(
                ["cmd.exe", "/c", str(ROOT / "tools" / "rabbitmq_server-4.3.6" / "sbin" / "rabbitmqctl.bat"), "stop"],
                env=mq_env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False, timeout=10,
            )
            kill_proc(mq_proc)
        kill_proc(redis_proc)
        shutil.rmtree(work_dir, ignore_errors=True)


if __name__ == "__main__":
    main()
