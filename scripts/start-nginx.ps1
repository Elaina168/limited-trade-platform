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
$logs = Join-Path $projectRoot "$directory/logs"
$temp = Join-Path $projectRoot "$directory/temp"
if (!(Test-Path -LiteralPath $nginx)) { throw '找不到tools/nginx-1.30.6/nginx.exe，请先准备官方Windows便携版。' }
$pidFile = Join-Path $logs 'nginx.pid'
# nginx -t可能留下空PID文件，非空文件才表示需要检查已有实例。
if ((Test-Path -LiteralPath $pidFile) -and ![string]::IsNullOrWhiteSpace((Get-Content -Raw -Encoding UTF8 $pidFile))) {
    throw '本项目已有nginx.pid，请先检查运行状态，不重复启动。'
}
New-Item -ItemType Directory -Force -Path @($logs, $temp) | Out-Null
& $nginx -p $prefix -c nginx.conf -t
if ($LASTEXITCODE -ne 0) { throw 'Nginx配置检查失败，未启动。' }
Start-Process -FilePath $nginx -ArgumentList @('-p', "`"$prefix`"", '-c', 'nginx.conf') -WorkingDirectory $projectRoot -WindowStyle Hidden | Out-Null
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    if (Test-Path -LiteralPath $pidFile) {
        $masterText = Get-Content -Raw -Encoding UTF8 $pidFile
        if (![string]::IsNullOrWhiteSpace($masterText)) {
            $masterId = [int]$masterText
            Get-Process -Id $masterId -ErrorAction Stop | Out-Null
            $port = switch ($Mode) { 'single' { 18080 } 'cluster' { 18081 } 'trade-drill' { 18082 } }
            Write-Output "Nginx已启动：http://127.0.0.1:$port/，仅Nginx健康入口为/nginx-health。"
            return
        }
    }
    Start-Sleep -Milliseconds 100
}
throw "未检测到Nginx主进程PID，请检查$directory/logs/error.log。"
