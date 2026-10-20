#Requires -Version 7.4
param(
    [Parameter(Mandatory)][ValidateSet('Backup', 'Restore', 'Verify')][string]$Action,
    [string]$BackupDirectory,
    [string]$TargetDatabase,
    [string]$MySqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
)

chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$backupRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'tools/mysql-backups'))
if (!$BackupDirectory) {
    if ($Action -ne 'Backup') { throw 'Restore/Verify必须指定-BackupDirectory。' }
    $BackupDirectory = Join-Path $backupRoot ((Get-Date -Format 'yyyyMMdd_HHmmss') + '_' + [guid]::NewGuid().ToString('N').Substring(0, 8))
}
$BackupDirectory = [IO.Path]::GetFullPath($BackupDirectory)
if (!$BackupDirectory.StartsWith($backupRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw '备份目录必须位于本项目tools/mysql-backups的子目录。'
}
# 拒绝经过链接写入意外位置；仅对本轮新建的空目录收紧ACL。
$ancestor = $BackupDirectory
while ($ancestor) {
    if (Test-Path -LiteralPath $ancestor) {
        if ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw '备份路径不允许包含符号链接或目录联接。'
        }
    }
    $ancestor = Split-Path $ancestor -Parent
}
$java = (Get-Command java.exe -ErrorAction Stop).Source
$connector = Join-Path $projectRoot 'tools/maven-repository/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar'
foreach ($file in @($connector, "$MySqlBin/mysql.exe", "$MySqlBin/mysqldump.exe", "$projectRoot/config/db.properties")) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "缺少已有组件或配置：$file" }
}
if ($Action -eq 'Backup') {
    if (Test-Path -LiteralPath $BackupDirectory) { throw 'Backup要求全新目录，不覆盖已有备份。' }
    New-Item -ItemType Directory -Path $BackupDirectory | Out-Null
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.SetOwner($owner)
    foreach ($identity in @($owner, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $BackupDirectory -AclObject $acl
} elseif (!(Test-Path -LiteralPath "$BackupDirectory/manifest.properties" -PathType Leaf)) {
    throw '找不到已完成备份的manifest.properties。'
}
# 复制进来的备份可能继承宽松权限；不能在这样的目录生成临时密码文件。
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$existingAcl = Get-Acl -LiteralPath $BackupDirectory
$allowedIdentities = @($currentUser, 'S-1-5-18')
if (!$existingAcl.AreAccessRulesProtected -or
    $existingAcl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $currentUser) {
    throw 'SECURE_DIRECTORY：备份目录必须由当前用户持有并关闭ACL继承，请使用Backup生成的私有目录。'
}
foreach ($rule in $existingAcl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
    if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -notin $allowedIdentities) {
        throw 'SECURE_DIRECTORY：备份目录存在其他账户的访问权限，未生成密码文件。'
    }
}
& $java '--class-path' $connector "$PSScriptRoot/DatabaseBackupTool.java" $Action "$projectRoot/config/db.properties" $MySqlBin $BackupDirectory $TargetDatabase
if ($LASTEXITCODE -ne 0) { throw "数据库工具失败，退出码$LASTEXITCODE；诊断在备份目录tool-error.log。" }
