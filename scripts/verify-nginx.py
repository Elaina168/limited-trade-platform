"""只验证本机Nginx入口，复用本机普通账号，不新增订单或修改业务数据。"""
import http.cookiejar
import json
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid

root = Path(__file__).resolve().parent.parent
base = "http://127.0.0.1:18080"
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))


def request(path, method="GET", data=None, headers=None):
    req = urllib.request.Request(base + path, data=data, headers=headers or {}, method=method)
    try:
        response = client.open(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read()


status, _, body = request("/nginx-health")
assert status == 200 and json.loads(body)["status"] == "UP"
status, _, body = request("/")
assert status == 200 and b'/assets/app.js' in body
status, _, body = request("/assets/app.js")
assert status == 200 and b'import ' in body
status, headers, body = request("/api/ping")
assert status == 200 and json.loads(body)["message"] == "pong"
trace = headers["X-Trace-Id"]
assert re.fullmatch("[0-9a-f]{32}", trace)
assert len(headers.get_all("X-Trace-Id")) == 1
status, _, _ = request("/api/auth/me")
assert status == 401
account = json.loads((root / "config/local-accounts.txt").read_text(encoding="utf-8-sig"))["user"]
form = urllib.parse.urlencode({"username": account["username"], "password": account["password"]}).encode()
status, _, _ = request("/api/auth/login", "POST", form, {"Content-Type": "application/x-www-form-urlencoded"})
assert status == 403, "缺少CSRF令牌必须拒绝登录"
status, _, body = request("/api/auth/csrf")
assert status == 200
csrf = json.loads(body)
status, _, _ = request("/api/auth/login", "POST", form, {"Content-Type": "application/x-www-form-urlencoded", csrf["headerName"]: csrf["token"]})
assert status == 204
try:
    status, _, body = request("/api/auth/me")
    assert status == 200 and json.loads(body)["username"] == account["username"]
    status, _, _ = request("/api/admin/products")
    assert status == 403, "普通账号不能获得管理权限"
    marker = "nginx-private-query-" + uuid.uuid4().hex
    status, _, _ = request("/api/products/search?" + urllib.parse.urlencode({"keyword": marker}))
    assert status == 200
finally:
    status, _, body = request("/api/auth/csrf")
    assert status == 200
    csrf = json.loads(body)
    status, _, _ = request("/api/auth/logout", "POST", headers={csrf["headerName"]: csrf["token"]})
    assert status == 204
status, _, _ = request("/api/auth/me")
assert status == 401
text = (root / "deploy/nginx/logs/access.log").read_text(encoding="utf-8")
records = [json.loads(line) for line in text.splitlines()]
match = next(row for row in records if row["traceId"] == trace)
assert match["status"] == 200 and match["upstreamStatus"] == "200"
assert marker not in text and account["password"] not in text
application = (root / "logs/limited-trade-platform.log").read_text(encoding="utf-8")
assert any(json.loads(line).get("traceId") == trace for line in application.splitlines() if line.startswith("{"))
print("PASS Nginx页面/API转发、CSRF/Session/角色/退出、唯一追踪响应头、双端日志关联及访问日志敏感字段排除。")
