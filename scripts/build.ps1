$ErrorActionPreference='Stop'
chcp 65001 > $null
[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new()
$root=Split-Path $PSScriptRoot -Parent
$maven=Join-Path (Split-Path $root -Parent) 'limited-trade-platform/tools/apache-maven-3.9.16/bin/mvn.cmd'
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
$env:MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
Push-Location $root
try{
    & $maven -B -ntp package
    if($LASTEXITCODE -ne 0){throw "独立工程构建失败，退出码$LASTEXITCODE"}
}finally{Pop-Location}
