param([switch]$Foreground)

chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:ERLANG_HOME = Join-Path $projectRoot 'tools/Erlang OTP'
$env:RABBITMQ_BASE = Join-Path $projectRoot 'tools/rabbitmq-runtime'
$env:RABBITMQ_CONFIG_FILE = Join-Path $env:RABBITMQ_BASE 'rabbitmq'
$env:RABBITMQ_ENABLED_PLUGINS_FILE = Join-Path $env:RABBITMQ_BASE 'enabled_plugins'
$env:RABBITMQ_NODENAME = 'limited_trade@localhost'
$env:ERL_EPMD_ADDRESS = '127.0.0.1'
$env:RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS = '+S 4:4 -kernel inet_dist_use_interface {127,0,0,1}'
$brokerBin = Join-Path $projectRoot 'tools/rabbitmq_server-4.3.6/sbin'
if (!(Test-Path -LiteralPath (Join-Path $env:ERLANG_HOME 'bin/erl.exe'))) {
    throw "找不到 Erlang：$env:ERLANG_HOME"
}
New-Item -ItemType Directory -Force -Path $env:RABBITMQ_BASE | Out-Null
$brokerConfig = Join-Path $env:RABBITMQ_BASE 'rabbitmq.conf'
if (!(Test-Path -LiteralPath $brokerConfig)) {
    @'
listeners.tcp.local = 127.0.0.1:5672
management.tcp.ip = 127.0.0.1
management.tcp.port = 15672
distribution.listener.port_range.min = 25672
distribution.listener.port_range.max = 25672
'@ | Set-Content -LiteralPath $brokerConfig -Encoding UTF8
}
if (!(Test-Path -LiteralPath $env:RABBITMQ_ENABLED_PLUGINS_FILE)) {
    '[rabbitmq_management].' | Set-Content -LiteralPath $env:RABBITMQ_ENABLED_PLUGINS_FILE -Encoding UTF8
}
if ($Foreground) {
    & (Join-Path $brokerBin 'rabbitmq-server.bat')
    if ($LASTEXITCODE -ne 0) { throw "RabbitMQ 退出，代码 $LASTEXITCODE" }
} else {
    # 后台进程继承本脚本的临时环境；不注册服务，也不修改系统 PATH。
    $shellPath = (Get-Process -Id $PID).Path
    Start-Process -FilePath $shellPath -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"", '-Foreground') `
        -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:RABBITMQ_BASE 'console.log') `
        -RedirectStandardError (Join-Path $env:RABBITMQ_BASE 'console-error.log') | Out-Null
    Write-Output 'RabbitMQ 启动已发起；请检查 tools/rabbitmq-runtime/console.log。'
}
