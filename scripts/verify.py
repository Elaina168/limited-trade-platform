"""真实本机多进程验收；不访问交易数据库，不使用Docker。"""
import json
from pathlib import Path
import subprocess
import socket
import time
import traceback
import urllib.error
import urllib.request
from decimal import Decimal
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "runtime"
PWSH = r"C:\Users\zytx\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe"
PORTS = {"registry":18760,"config-server":18761,"product-a":18770,"product-b":18771,"preview":18780,"gateway-a":18800,"gateway-b":18801}
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
REPORT = {"startedAt":datetime.now(timezone(timedelta(hours=8))).isoformat(),"checks":{},"scope":"localhost-only independent cloud lab"}


def command(action, node="all", *options):
    args = [PWSH,"-NoProfile","-File",str(ROOT/"scripts/lab.ps1"),"-Action",action,"-Node",node,*options]
    # 启动的JVM会继承部分Windows句柄；使用文件记录，避免communicate等管道EOF。
    log=RUNTIME/f"command-{action}-{node}-{time.time_ns()}.log"
    with log.open("w",encoding="utf-8") as output:
        result=subprocess.run(args,cwd=ROOT,stdout=output,stderr=subprocess.STDOUT)
    text=log.read_text(encoding="utf-8")
    print(text.strip(),flush=True)
    if result.returncode:
        raise RuntimeError(f"lab.ps1 {action} {node} failed: {text}")


def request(port,path,method="GET",data=None,headers=None):
    payload = json.dumps(data).encode() if data is not None else None
    req=urllib.request.Request(f"http://127.0.0.1:{port}{path}",data=payload,method=method,
        headers={"Content-Type":"application/json","Accept":"application/json",**(headers or {})})
    started=time.monotonic()
    try:
        response=OPENER.open(req,timeout=4)
    except urllib.error.HTTPError as error:
        response=error
    with response:
        raw=response.read().decode("utf-8")
        return {"status":response.status,"headers":dict(response.headers),"body":json.loads(raw) if raw else None,
                "seconds":round(time.monotonic()-started,3)}


def preview(port=18780,quantity=3,product=1,headers=None):
    return request(port,"/api/previews","POST",{"productId":product,"quantity":quantity},headers)


def wait_for(description,probe,predicate,seconds=45):
    deadline=time.monotonic()+seconds
    last=None
    while time.monotonic()<deadline:
        try:
            last=probe()
            if predicate(last):
                return last
        except (urllib.error.URLError,TimeoutError) as error:
            last=str(error)
        time.sleep(.4)
    raise AssertionError(f"{description} timeout: {last}")


def products():
    result=request(18760,"/eureka/apps/PRODUCT-SERVICE")
    if result["status"]==404:
        return []
    return result["body"]["application"]["instance"]


def mode(value):
    for port in (18770,18771):
        assert request(port,"/lab/mode","PUT",{"mode":value})["status"]==200


def circuit():
    return request(18780,"/lab/circuit")["body"]


def check(name,value):
    REPORT["checks"][name]=value
    print(f"PASS {name}",flush=True)
    (RUNTIME/"verification.json").write_text(json.dumps(REPORT,ensure_ascii=False,indent=2),encoding="utf-8")


def verify():
    wait_for("two registered products",products,lambda rows:len(rows)==2)
    first=wait_for("gateway chain",lambda:preview(18800),lambda r:r["status"]==200)
    assert Decimal(str(first["body"]["totalAmount"]))==Decimal("59.70"),first
    assert first["body"]["name"]=="Spring Cloud实验商品",first
    assert first["body"]["campaign"]=="cloud-lab-v1",first
    trace=first["body"]["traceId"]
    assert first["headers"]["X-Trace-Id"]==trace
    check("gateway_preview_product",first)
    seen=set()
    for _ in range(12):
        result=preview(18800)
        assert result["status"]==200,result
        seen.add(result["body"]["productInstance"])
    assert seen=={"product-a","product-b"},seen
    check("load_balancing",sorted(seen))
    assert preview(quantity=0)["status"]==400
    missing=preview(product=999)
    assert missing["status"]==404,missing
    assert circuit()["failedCalls"]==0,circuit()
    check("validation_and_4xx_not_circuit_failure",True)
    matching=[]
    for name in ("gateway-a","preview",first["body"]["productInstance"]):
        rows=[]
        for log in RUNTIME.glob(f"{name}-*.out.log"):
            rows.extend(line for line in log.read_text(encoding="utf-8").splitlines() if trace in line)
        assert rows,(name,trace)
        matching.extend(rows)
    check("trace_correlation",matching)
    mode("slow")
    failures=[preview() for _ in range(4)]
    # 已有4次成功的滚动窗口中，新增2次失败即达到50%，随后请求被熔断。
    assert all(r["status"]==504 and r["seconds"]<2.5 for r in failures[:2]),failures
    assert all(r["status"]==503 and r["body"]["error"]=="CIRCUIT_OPEN" for r in failures[2:]),failures
    assert circuit()["state"]=="OPEN",circuit()
    blocked=preview()
    assert blocked["status"]==503 and blocked["body"]["error"]=="CIRCUIT_OPEN",blocked
    assert blocked["seconds"]<.5,blocked
    check("timeout_and_circuit_open",{"slow":failures,"blocked":blocked})
    mode("normal")
    wait_for("circuit recovers",preview,lambda r:r["status"]==200,seconds=12)
    assert preview()["status"]==200
    assert circuit()["state"]=="CLOSED",circuit()
    check("half_open_recovery",circuit())
    mode("fail")
    failed=[preview() for _ in range(4)]
    assert all(r["status"]==502 for r in failed),failed
    assert circuit()["state"]=="OPEN"
    mode("normal")
    wait_for("server failure recovery",preview,lambda r:r["status"]==200,seconds=12)
    assert preview()["status"]==200
    check("server_failure_and_recovery",[r["status"] for r in failed])
    command("Stop","product-a")
    began=time.monotonic()
    samples=[]
    deadline=began+50
    consecutive=0
    while time.monotonic()<deadline:
        result=preview()
        elapsed=round(time.monotonic()-began,3)
        samples.append({"elapsed":elapsed,"status":result["status"],"instance":result["body"].get("productInstance")})
        if result["status"]==200 and result["body"]["productInstance"]=="product-b" and len(products())==1:
            consecutive+=1
        else:
            consecutive=0
        if consecutive==8:
            break
        time.sleep(.5)
    assert consecutive==8,samples
    check("instance_failure_window",{"stableAfterSeconds":samples[-8]["elapsed"],"samples":samples})
    command("Start","product-a","-Drill")
    wait_for("product re-registers",products,lambda rows:len(rows)==2)
    seen=set()
    def probe_restored():
        result=preview()
        if result["status"]==200:
            seen.add(result["body"]["productInstance"])
        return sorted(seen)
    wait_for("restored products receive requests",probe_restored,lambda rows:len(rows)==2)
    check("instance_restore",sorted(seen))
    config=ROOT/"config-repo/preview-service.properties"
    original=config.read_bytes()
    try:
        config.write_text("lab.campaign=cloud-lab-v2\n",encoding="utf-8")
        # native配置已更新，但现有Bean不做热刷新。
        assert preview()["body"]["campaign"]=="cloud-lab-v1"
        command("Stop","preview")
        command("Start","preview","-Drill")
        value=wait_for("config after restart",preview,lambda r:r["status"]==200 and r["body"]["campaign"]=="cloud-lab-v2")
        check("config_restart_effect",value["body"]["campaign"])
    finally:
        config.write_bytes(original)
    command("Stop","gateway-a")
    command("Stop","gateway-b")
    command("Start","gateway-a","-TotalLimit","2","-IpLimit","100","-LimitPeriod","60s")
    command("Start","gateway-b","-TotalLimit","100","-IpLimit","2","-LimitPeriod","60s")
    # 先用不计额度的只读路径等待网关发现完成。
    for port in (18800,18801):
        wait_for("gateway product route",lambda:request(port,"/api/products/1"),lambda r:r["status"]==200)
        wrong_method=request(port,"/api/previews")
        assert wrong_method["status"]==405,wrong_method
    allowed=[]
    for port in (18800,18801):
        results=[preview(port,headers={"X-Forwarded-For":f"198.51.100.{i}"}) for i in range(3)]
        assert [r["status"] for r in results]==[200,200,429],results
        allowed.append(results)
        assert request(port,"/api/products/1")["status"]==200
        alias=request(port,"/api/previews;x=1","POST",{"productId":1,"quantity":1})
        assert alias["status"]==429,alias
    assert allowed[0][-1]["headers"]["X-RateLimit-Scope"]=="total"
    assert allowed[1][-1]["headers"]["X-RateLimit-Scope"]=="ip"
    check("gateway_total_ip_and_local_budgets",allowed)
    for port in (18800,18801):
        assert request(port,"/lab/mode","PUT",{"mode":"fail"})["status"]==404
    check("drill_not_routed_by_gateway",True)


if __name__=="__main__":
    RUNTIME.mkdir(exist_ok=True)
    if list(RUNTIME.glob("*.process.json")):
        raise SystemExit("验收要求实验进程已停止，请先确认并执行lab.ps1 -Action Stop")
    started=False
    try:
        started=True
        command("Start","all","-Drill","-TotalLimit","1000","-IpLimit","1000")
        verify()
        REPORT["result"]="PASS"
    except BaseException as error:
        REPORT["result"]="FAIL"
        REPORT["failure"]=f"{type(error).__name__}: {error}"
        traceback.print_exc()
        raise
    finally:
        try:
            if started:
                command("Stop")
                assert not list(RUNTIME.glob("*.process.json"))
                for port in PORTS.values():
                    with socket.socket() as sock:
                        sock.settimeout(.3)
                        assert sock.connect_ex(("127.0.0.1",port))!=0,port
                check("cleanup",True)
        except BaseException as cleanup_error:
            REPORT["result"]="FAIL"
            REPORT["cleanupFailure"]=f"{type(cleanup_error).__name__}: {cleanup_error}"
            raise
        finally:
            (RUNTIME/"verification.json").write_text(json.dumps(REPORT,ensure_ascii=False,indent=2),encoding="utf-8")
