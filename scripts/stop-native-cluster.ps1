#Requires -Version 7.4
param([ValidateSet('all', 'node-a', 'node-b')][string]$Instance = 'all')

chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $projectRoot 'tools/native-cluster'
$names = if ($Instance -eq 'all') { @('node-a', 'node-b') } else { @($Instance) }
$entries = @()
foreach ($name in $names) {
    $stateFile = Join-Path $runtime "$name.json"
    if (Test-Path -LiteralPath $stateFile) {
        $entries += Get-Content -LiteralPath $stateFile -Raw -Encoding UTF8 | ConvertFrom-Json
    } elseif ($Instance -ne 'all') {
        throw "没有${name}的本项目进程记录，不接管其他Java。"
    }
}
if (!$entries.Count) { throw '没有本项目管理的Java实例。' }
foreach ($entry in $entries) {
    $process = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
    if ($null -ne $process -and ($process.Path -ne $entry.path -or $process.StartTime.ToUniversalTime().Ticks -ne $entry.startedTicks)) {
        throw "PID $($entry.id)与本项目记录不匹配，未停止任何Java。"
    }
}
foreach ($entry in $entries) {
    $process = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
    if ($null -ne $process) {
        $null = $process.Handle
        Stop-Process -Id $entry.id -ErrorAction Stop
        if (!$process.WaitForExit(10000)) { throw "$($entry.name)停止超时。" }
    }
    Remove-Item -LiteralPath (Join-Path $runtime "$($entry.name).json")
    Write-Output "$($entry.name)已停止。"
}
Write-Output '未停止其他Java、Redis、RabbitMQ、Nginx或日志组件。'
