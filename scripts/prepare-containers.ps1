param([ValidatePattern('^[a-z0-9][a-z0-9_-]{0,47}$')][string]$Instance)

if ($env:OS -eq 'Windows_NT') { chcp 65001 > $null }
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$configPath = Join-Path $projectRoot 'config/container'
$envPath = Join-Path $projectRoot '.env.docker'
if ($Instance) {
    $configPath = Join-Path $configPath $Instance
    $envPath = Join-Path $configPath '.env.docker'
}

# 不覆盖已有容器凭据，防止与已初始化的数据卷密码不一致。
if ((Test-Path -LiteralPath $envPath) -or (Test-Path -LiteralPath $configPath)) {
    throw '容器配置已存在，请复用原配置；本脚本不会重新生成密码。'
}

$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $passwords = 1..3 | ForEach-Object {
        $bytes = New-Object byte[] 24
        $random.GetBytes($bytes)
        [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
    }
} finally {
    $random.Dispose()
}

$utf8 = [System.Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Path $configPath | Out-Null
[System.IO.File]::WriteAllText($envPath, @"
MYSQL_ROOT_PASSWORD=$($passwords[0])
MYSQL_PASSWORD=$($passwords[1])
RABBITMQ_DEFAULT_PASS=$($passwords[2])
"@, $utf8)
if ($Instance) {
    [System.IO.File]::AppendAllText($envPath, [char]10 + "CONTAINER_CONFIG_DIR=./config/container/$Instance" + [char]10, $utf8)
}
[System.IO.File]::WriteAllText((Join-Path $configPath 'db.properties'), @"
db.url=jdbc:mysql://mysql:3306/limited_trade_platform?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
db.username=limited_trade
db.password=$($passwords[1])
"@, $utf8)
[System.IO.File]::WriteAllText((Join-Path $configPath 'rabbitmq.properties'), @"
mq.host=rabbitmq
mq.port=5672
mq.username=limited_trade
mq.password=$($passwords[2])
mq.virtual-host=limited-trade
mq.test-virtual-host=limited-trade-test
"@, $utf8)

Write-Output ('已生成容器配置，环境文件 ' + $envPath)
