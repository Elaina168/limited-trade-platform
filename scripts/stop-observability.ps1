#Requires -Version 7.4
chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$stateFile = Join-Path $projectRoot 'tools/observability-runtime/processes.json'
if (!(Test-Path -LiteralPath $stateFile)) { throw '没有本项目的日志组件进程记录。' }
$entries = @(Get-Content -Raw -Encoding UTF8 $stateFile | ConvertFrom-Json)
[array]::Reverse($entries)
# 先核实全部存活进程的身份，再依次停止采集、界面和存储。
foreach ($entry in $entries) {
    $process = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
    if ($null -ne $process -and ($process.Path -ne $entry.path -or $process.StartTime.ToUniversalTime().Ticks -ne $entry.startedTicks)) {
        throw "PID $($entry.id)与本项目记录不匹配，未停止任何进程。"
    }
}
foreach ($entry in $entries) {
    $process = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
    if ($null -ne $process) {
        # 提前持有进程句柄，避免按PID等待时遇到进程已退出的竞争。
        $null = $process.Handle
        Stop-Process -Id $entry.id -ErrorAction Stop
        if (!$process.WaitForExit(10000)) { throw "$($entry.name)停止超时。" }
    }
    Write-Output "$($entry.name)已停止。"
}
Remove-Item -LiteralPath $stateFile
Write-Output '读取位置、日志数据和Grafana账号保留，未停止Java、Redis或其他项目进程。'
