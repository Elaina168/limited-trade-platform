chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:ERLANG_HOME = Join-Path $projectRoot 'tools/Erlang OTP'
$env:RABBITMQ_BASE = Join-Path $projectRoot 'tools/rabbitmq-runtime'
$env:RABBITMQ_NODENAME = 'limited_trade@localhost'
$env:ERL_EPMD_ADDRESS = '127.0.0.1'
& (Join-Path $projectRoot 'tools/rabbitmq_server-4.3.6/sbin/rabbitmqctl.bat') stop
if ($LASTEXITCODE -ne 0) { throw "停止项目 RabbitMQ 节点失败，代码 $LASTEXITCODE" }
