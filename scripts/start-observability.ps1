#Requires -Version 7.4
chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$ErrorActionPreference = 'Stop'
$projectRoot = (Split-Path $PSScriptRoot -Parent).Replace('\', '/')
$runtime = "$projectRoot/tools/observability-runtime"
$stateFile = "$runtime/processes.json"
$configRoot = "$projectRoot/deploy/observability"
$manifest = Get-Content -Raw -Encoding UTF8 "$configRoot/tools.json" | ConvertFrom-Json
if (Test-Path -LiteralPath $stateFile) { throw '已有日志组件进程记录，请先运行stop-observability.ps1，不重复启动。' }
foreach ($port in @(3000, 3100, 9096, 12345)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "端口${port}已占用，未启动或接管任何组件。"
    }
}
foreach ($tool in $manifest.tools) {
    if (!(Test-Path -LiteralPath "$projectRoot/$($tool.executable)")) {
        throw "缺少$($tool.name) $($tool.version)，请按deploy/observability/tools.json准备官方程序。"
    }
}
New-Item -ItemType Directory -Force @($runtime, "$runtime/grafana/data", "$runtime/grafana/logs", "$runtime/grafana/plugins") | Out-Null
$env:PROJECT_ROOT = $projectRoot
$env:OBS_ROOT = $runtime
$alloy = "$projectRoot/$($manifest.tools.Where({ $_.name -eq 'alloy' }).executable)"
$loki = "$projectRoot/$($manifest.tools.Where({ $_.name -eq 'loki' }).executable)"
$grafana = "$projectRoot/$($manifest.tools.Where({ $_.name -eq 'grafana' }).executable)"
$grafanaHome = Split-Path (Split-Path $grafana -Parent) -Parent
& $alloy validate "$configRoot/config.alloy"
if ($LASTEXITCODE -ne 0) { throw 'Alloy配置验证失败，未启动。' }
& $loki "-config.file=$configRoot/loki.yml" '-config.expand-env=true' '-verify-config=true'
if ($LASTEXITCODE -ne 0) { throw 'Loki配置验证失败，未启动。' }
$credentialsFile = "$runtime/grafana-credentials.json"
if (!(Test-Path -LiteralPath $credentialsFile)) {
    # 只在第一次启动生成，后续复用；不把密码放入命令行或输出。
    @{
        username = 'admin'
        password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
        secretKey = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    } | ConvertTo-Json | Set-Content -Encoding utf8NoBOM $credentialsFile
}
$credentials = Get-Content -Raw -Encoding UTF8 $credentialsFile | ConvertFrom-Json
$components = @(
    @{ Name = 'loki'; Exe = $loki; Args = @("-config.file=`"$configRoot/loki.yml`"", '-config.expand-env=true'); Health = 'http://127.0.0.1:3100/ready'; Env = @{} },
    @{ Name = 'grafana'; Exe = $grafana; Args = @('server', '--homepath', "`"$grafanaHome`"", '--config', "`"$configRoot/grafana.ini`""); Health = 'http://127.0.0.1:3000/api/health'; Env = @{ GF_SECURITY_ADMIN_PASSWORD = $credentials.password; GF_SECURITY_SECRET_KEY = $credentials.secretKey } },
    @{ Name = 'alloy'; Exe = $alloy; Args = @('run', '--disable-reporting', '--server.http.listen-addr=127.0.0.1:12345', "--storage.path=`"$runtime/alloy`"", "`"$configRoot/config.alloy`""); Health = 'http://127.0.0.1:12345/-/ready'; Env = @{} }
)
$owned = @()
try {
    foreach ($component in $components) {
        $process = Start-Process -FilePath $component.Exe -ArgumentList $component.Args -WorkingDirectory $projectRoot -Environment $component.Env -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/$($component.Name).stdout.log" -RedirectStandardError "$runtime/$($component.Name).stderr.log"
        $owned += @{ name = $component.Name; id = $process.Id; path = $process.Path; startedTicks = $process.StartTime.ToUniversalTime().Ticks }
        ConvertTo-Json -InputObject @($owned) | Set-Content -Encoding utf8NoBOM $stateFile
        $ready = $false
        $lastReadiness = '未收到就绪响应'
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            $process.Refresh()
            if ($process.HasExited) { throw "$($component.Name)提前退出，请检查${runtime}中的组件日志。" }
            try {
                $response = Invoke-WebRequest $component.Health -TimeoutSec 2 -SkipHttpErrorCheck
                $lastReadiness = "HTTP $($response.StatusCode)"
                if ($response.StatusCode -eq 200) { $ready = $true; break }
            } catch [System.Net.Http.HttpRequestException], [System.Threading.Tasks.TaskCanceledException] {
                # 刚启动时连接尚未建立，记录最近错误，超时后明确失败。
                $lastReadiness = $_.Exception.Message
            }
            Start-Sleep -Seconds 1
        }
        if (!$ready) { throw "$($component.Name)就绪超时：$lastReadiness。检查${runtime}中的组件日志。" }
        Write-Output "$($component.Name)已就绪。"
    }
} catch {
    # 只清理由本次启动并且身份仍匹配的进程；原始错误继续抛出。
    foreach ($entry in $owned) {
        $process = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
        if ($null -ne $process -and $process.Path -eq $entry.path -and $process.StartTime.ToUniversalTime().Ticks -eq $entry.startedTicks) {
            Stop-Process -Id $entry.id -ErrorAction Stop
        }
    }
    if (Test-Path -LiteralPath $stateFile) { Remove-Item -LiteralPath $stateFile -ErrorAction Stop }
    throw
}
Write-Output "日志查询入口：http://127.0.0.1:3000/；账号admin，密码保存在$credentialsFile。"
