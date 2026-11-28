param([string[]]$Goals=@('package'))

$ErrorActionPreference='Stop'
chcp 65001 | Out-Null
[Console]::OutputEncoding=[Text.UTF8Encoding]::new()
$root=Split-Path $PSScriptRoot -Parent
$trade=Join-Path (Split-Path $root -Parent) 'limited-trade-platform'
$maven=if($env:LAB_MAVEN){$env:LAB_MAVEN}else{Join-Path $trade 'tools/apache-maven-3.9.16/bin/mvn.cmd'}
$repo=if($env:LAB_MAVEN_REPO){$env:LAB_MAVEN_REPO}else{Join-Path $trade 'tools/maven-repository'}
if(!$env:JAVA_HOME){$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'}
$env:MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
Push-Location $root
try {
    & $maven -B -ntp "-Dmaven.repo.local=$repo" @Goals
    if($LASTEXITCODE -ne 0){throw "独立工程构建失败，退出码$LASTEXITCODE"}
} finally {
    Pop-Location
}
