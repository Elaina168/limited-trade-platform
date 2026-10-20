"""显式运行的真实备份恢复验收；保留备份与新恢复库，不修改开发库。"""
import http.cookiejar
import json
import locale
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
entry = root / "scripts/database-backup.ps1"


def invoke(action, directory, target=None, success=True):
    command = ["pwsh", "-NoProfile", "-File", str(entry), "-Action", action,
               "-BackupDirectory", str(directory)]
    if target:
        command += ["-TargetDatabase", target]
    result = subprocess.run(command, cwd=root, capture_output=True, text=True, encoding="utf-8", timeout=120)
    assert (result.returncode == 0) == success, result.stdout + result.stderr
    return result.stdout + result.stderr


def request(client, path, method="GET", data=None, headers=None):
    with client.open(urllib.request.Request("http://127.0.0.1:8085" + path, data=data,
                                          headers=headers or {}, method=method), timeout=5) as response:
        body = response.read()
        return response.status, json.loads(body) if body else None


if __name__ == "__main__":
    assert entry.exists(), "缺少本阶段database-backup.ps1入口"
    token = uuid.uuid4().hex[:12]
    directory = root / "tools/mysql-backups" / ("drill_" + token)
    target = "limited_trade_restore_" + token
    # 空目录夹具不放备份数据；继承普通权限时必须在创建密码文件前拒绝。
    public_directory = root / "tools/mysql-backups" / ("acl_reject_" + token)
    public_directory.mkdir(parents=True)
    (public_directory / "manifest.properties").write_text("", encoding="utf-8")
    try:
        assert "SECURE_DIRECTORY" in invoke("Verify", public_directory, success=False)
    finally:
        for file in public_directory.iterdir():
            file.unlink()
        public_directory.rmdir()
    invoke("Backup", directory)
    invoke("Restore", directory, target)
    invoke("Verify", directory, target)
    assert "TARGET_EXISTS" in invoke("Restore", directory, target, success=False)
    assert "TARGET_NAME" in invoke("Restore", directory, "limited_trade_platform", success=False)
    sql_file = directory / "database.sql"
    original = sql_file.read_bytes()
    rejected_target = target + "_bad"
    try:
        sql_file.write_bytes(original + b"\n-- changed by checksum test\n")
        assert "CHECKSUM" in invoke("Restore", directory, rejected_target, success=False)
    finally:
        sql_file.write_bytes(original)
    # 同一名称随后可以成功CREATE，证明校验失败时未提前创建数据库。
    invoke("Restore", directory, rejected_target)
    invoke("Verify", directory, rejected_target)
    invoke("Verify", directory)

    # 使用真实java.home，避免把Oracle转发器当作后台JVM。
    result = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True,
                            text=True, encoding=locale.getencoding(), check=True)
    java_home = re.search(r"(?m)^\s*java\.home = (.+)$", result.stderr).group(1).strip()
    java = str(Path(java_home) / "bin/java.exe")
    account = json.loads((root / "config/local-accounts.txt").read_text(encoding="utf-8-sig"))["user"]
    # 未占用才启动，绝不接管其他服务。
    import socket
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 8085))
    environment = os.environ.copy()
    environment.update({"APP_INSTANCE_ID": "restore-drill", "APP_LOG_FILE": str(directory / "application.log")})
    command = [java, "-Xms64m", "-Xmx256m", "-jar", str(root / "target/limited-trade-platform-0.0.1-SNAPSHOT.jar"),
               "--server.address=127.0.0.1", "--server.port=8085", "--spring.profiles.active=default",
               "--app.scheduling.enabled=false", "--app.order-message.enabled=false",
               "--app.product-cache.enabled=false", "--spring.config.additional-location=" +
               (directory / ("application-" + rejected_target + ".properties")).as_uri()]
    # 显式选择第二个恢复库，两个恢复库均已逐表核对。
    with (directory / "application.stdout.log").open("w", encoding="utf-8") as output:
        process = subprocess.Popen(command, cwd=root, env=environment, stdout=output, stderr=subprocess.STDOUT)
        try:
            client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
            deadline = time.monotonic() + 60
            while True:
                assert process.poll() is None, "恢复应用提前退出，检查私有目录日志"
                try:
                    if request(client, "/api/ping")[0] == 200:
                        break
                except (urllib.error.URLError, TimeoutError):
                    if time.monotonic() >= deadline:
                        raise AssertionError("恢复应用就绪超时")
                    time.sleep(1)
            csrf = request(client, "/api/auth/csrf")[1]
            form = urllib.parse.urlencode({"username": account["username"], "password": account["password"]}).encode()
            assert request(client, "/api/auth/login", "POST", form,
                           {"Content-Type": "application/x-www-form-urlencoded", csrf["headerName"]: csrf["token"]})[0] == 204
            assert request(client, "/api/auth/me")[1]["username"] == account["username"]
            products = request(client, "/api/products")[1]
            orders = request(client, "/api/orders")[1]
            assert products, "当前备份应有在售商品"
            assert orders == [], "本次源库无订单，只验证空订单查询"
            csrf = request(client, "/api/auth/csrf")[1]
            assert request(client, "/api/auth/logout", "POST", headers={csrf["headerName"]: csrf["token"]})[0] == 204
            report = {"backup": str(directory), "restoredDatabases": [target, rejected_target],
                      "tableVerification": True, "overwriteRejected": True, "sourceNameRejected": True,
                      "checksumRejectedBeforeCreate": True, "login": True, "products": len(products),
                      "orders": len(orders), "applicationDatabase": rejected_target}
            (directory / "verification.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        finally:
            process.terminate()
            process.wait(timeout=20)
    invoke("Verify", directory)
    print(json.dumps(report, ensure_ascii=False, indent=2))
