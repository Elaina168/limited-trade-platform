param([ValidateSet('single', 'cluster', 'trade-drill')][string]$Mode = 'single')

chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$nginx = Join-Path $projectRoot 'tools/nginx-1.30.6/nginx.exe'
$directory = switch ($Mode) {
    'single' { 'deploy/nginx' }
    'cluster' { 'deploy/nginx-cluster' }
    'trade-drill' { 'deploy/nginx-trade-drill' }
}
$prefix = (Join-Path $projectRoot $directory).Replace('\', '/') + '/'
$pidFile = Join-Path $projectRoot "$directory/logs/nginx.pid"
if (!(Test-Path -LiteralPath $pidFile)) { throw '没有本项目的nginx.pid，请确认是否已经停止。' }
& $nginx -p $prefix -c nginx.conf -s quit
if ($LASTEXITCODE -ne 0) { throw 'Nginx停止信号发送失败。' }
Write-Output '已向本项目Nginx发送优雅退出信号，不停止Java应用或其他Nginx。'
