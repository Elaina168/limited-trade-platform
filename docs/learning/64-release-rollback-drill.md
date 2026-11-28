# 阶段 7.2 · 发布版本核验、回滚和消息积压恢复

Compose 在构建 JAR 时把 Git SHA、发布标签和最低迁移要求写入 `META-INF/build-info.properties`。默认从检出的 Git 引用取得 SHA。导出的源码包可在构建时显式传入 `APP_GIT_SHA`。打包后重启实例时，环境变量无法覆盖这些产物元数据。直接用 Maven 打包时可传 `-Dbuild.git.commit=<完整 SHA>`。

`GET /api/admin/operations/release` 向管理员展示产物元数据和数据库结构里程碑。结构检查只读访问当前库的 `information_schema`。`GET /internal/ready` 只返回状态码，由容器内部的健康探针调用。启用 `app.release.gate-traffic` 时，实例启动即校验必需迁移。校验失败时就绪探针和业务 API 都返回 `503`，日志记录 `release_schema_incompatible`。补迁移或切换产物后应重新启动实例。`/api/ping` 继续用于检查进程连通性。

## 隔离演练

`scripts/release-rollback-drill.py` 在独立端口启动 MySQL、Redis 和 RabbitMQ。它依次运行基线、升级、不兼容三个 JAR，再用升级 JAR 回滚。演练核对三个 JAR 的 SHA-256，比较正常实例报告与产物内部元数据，核对故障实例拒绝就绪和业务请求。回滚后验证订单与库存，再通过真实 Outbox 和消费队列触发积压告警、恢复关单与库存。

本地验证可从同一份源码构建三个演练产物，让最后一个产物声明尚未执行的迁移。实际发布可直接传入三个已归档的 JAR。

```powershell
$sha = git rev-parse HEAD
$artifacts = 'tools/release-artifacts'
New-Item -ItemType Directory -Force $artifacts | Out-Null
foreach ($label in @('r1', 'r2', 'r3-bad')) {
    $migration = if ($label -eq 'r3-bad') { '019_unapplied_breaking_migration' } else { '018_product_search_sync' }
    mvn.cmd -Pci "-Dbuild.git.commit=$sha" "-Dbuild.release.label=$label" "-Dbuild.required.migration=$migration" verify
    if ($LASTEXITCODE) { throw "构建失败 $label" }
    Copy-Item target/limited-trade-platform-0.0.1-SNAPSHOT.jar "$artifacts/$label.jar" -Force
}
$env:PYTHONIOENCODING = 'utf-8'
python scripts/release-rollback-drill.py "$artifacts/r1.jar" "$artifacts/r2.jar" "$artifacts/r3-bad.jar"
```

成功后，报告写入 `tools/release-drill/<runId>/summary.json`。它包含三份产物的 Git SHA 与 SHA-256、拦截状态码、订单与库存核对和恢复前后的消息告警。先前的单 JAR 配置切换记录保存在 `tools/release-drill/f7befee1ca/summary.json`。

2026-11-28 复验记录 `tools/release-drill/edc5029feb/summary.json`。三份 JAR 的 SHA-256 各不相同，故障产物的就绪及商品接口均返回 `503`。切回升级产物后，已支付单、待支付单及库存保持一致。MQ 积压和超时告警从各 2 项数据触发，恢复消费后两笔订单关单、库存回补，积压归零且告警列表清空。
