#Requires -Version 7.4
param([ValidateSet('all', 'node-a', 'node-b')][string]$Instance = 'all')

chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$ErrorActionPreference = 'Stop'
$projectRoot = (Split-Path $PSScriptRoot -Parent).Replace('\', '/')
$runtime = "$projectRoot/tools/native-cluster"
$jar = "$projectRoot/target/limited-trade-platform-0.0.1-SNAPSHOT.jar"
$java = (Get-Command java.exe -ErrorAction Stop).Source
# PATH可能指向Oracle转发器；读取实际java.home并直接启动JVM，才能准确管理PID。
$runtimeProperties = & $java '-XshowSettings:properties' '-version' 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { throw '无法读取当前Java运行时信息。' }
$javaHome = [regex]::Match($runtimeProperties, '(?m)^\s*java\.home = (.+)$')
if (!$javaHome.Success) { throw 'Java未返回java.home，不能确定实际JVM程序。' }
$java = (Resolve-Path -LiteralPath (Join-Path $javaHome.Groups[1].Value.Trim() 'bin/java.exe')).Path
$names = if ($Instance -eq 'all') { @('node-a', 'node-b') } else { @($Instance) }
if (!(Test-Path -LiteralPath $jar)) { throw '找不到可运行JAR，请先完成项目打包。' }
foreach ($name in $names) {
    $port = if ($name -eq 'node-a') { 8081 } else { 8082 }
    if (Test-Path -LiteralPath "$runtime/$name.json") { throw "${name}已有进程记录，请先检查或停止，不重复启动。" }
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "端口${port}已占用，不接管已有Java进程。"
    }
}
# 检查当前Windows能实际使用Redis，而不是仅在WSL内PING成功。
$redis = [Net.Sockets.TcpClient]::new()
try {
    if (!$redis.ConnectAsync('127.0.0.1', 6379).Wait(2000)) { throw 'Redis连接超时，请保持原WSL窗口运行。' }
    $stream = $redis.GetStream()
    $stream.ReadTimeout = 2000
    $message = [Text.Encoding]::ASCII.GetBytes("*1`r`n`$4`r`nPING`r`n")
    $stream.Write($message, 0, $message.Length)
    $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::ASCII)
    if ($reader.ReadLine() -ne '+PONG') { throw 'Redis没有返回PONG，未启动Java。' }
} finally {
    $redis.Dispose()
}
New-Item -ItemType Directory -Force @($runtime, "$projectRoot/logs") | Out-Null
$owned = @()
try {
    foreach ($name in $names) {
        $port = if ($name -eq 'node-a') { 8081 } else { 8082 }
        $arguments = @('-Xms64m', '-Xmx256m', '-jar', "`"$jar`"", '--spring.profiles.active=shared-session',
            '--server.address=127.0.0.1', "--server.port=$port", '--app.scheduling.enabled=false', '--app.order-message.enabled=false')
        $environment = @{
            APP_INSTANCE_ID = $name
            APP_LOG_FILE = "$projectRoot/logs/$name.log"
            SPRING_SESSION_REDIS_NAMESPACE = 'limited-trade:session:native-check:v1'
        }
        $process = Start-Process -FilePath $java -ArgumentList $arguments -Environment $environment -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/$name.stdout.log" -RedirectStandardError "$runtime/$name.stderr.log"
        $entry = @{ name = $name; id = $process.Id; path = $java; startedTicks = $process.StartTime.ToUniversalTime().Ticks; port = $port }
        $owned += $entry
        $entry | ConvertTo-Json | Set-Content -LiteralPath "$runtime/$name.json" -Encoding utf8NoBOM
        $ready = $false
        $lastReadiness = '尚未收到HTTP响应'
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            $process.Refresh()
            if ($process.HasExited) { throw "${name}提前退出，请检查${runtime}中的组件日志。" }
            try {
                $response = Invoke-WebRequest "http://127.0.0.1:$port/api/ping" -TimeoutSec 2 -SkipHttpErrorCheck
                $lastReadiness = "HTTP $($response.StatusCode)"
                if ($response.StatusCode -eq 200) {
                    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop
                    if ($listener.OwningProcess -ne $process.Id) { throw "${name}实际监听进程与启动记录不一致。" }
                    $ready = $true
                    break
                }
            } catch [System.Net.Http.HttpRequestException], [System.Threading.Tasks.TaskCanceledException] {
                # 启动期间连接未建立属于就绪等待，超时后保留最近错误并失败。
                $lastReadiness = $_.Exception.Message
            }
            Start-Sleep -Seconds 1
        }
        if (!$ready) { throw "${name}就绪超时：$lastReadiness" }
        Write-Output "${name}已就绪：http://127.0.0.1:$port/（检查模式，定时任务和MQ消费关闭）。"
    }
} catch {
    foreach ($entry in $owned) {
        $process = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
        if ($null -ne $process -and $process.Path -eq $entry.path -and $process.StartTime.ToUniversalTime().Ticks -eq $entry.startedTicks) {
            Stop-Process -Id $entry.id -ErrorAction Stop
        }
        Remove-Item -LiteralPath "$runtime/$($entry.name).json" -ErrorAction Stop
    }
    throw
}
Write-Output '只启动本项目Java实例，不安装或启动Redis、RabbitMQ、Nginx及日志组件。'
