param(
    [Parameter(Mandatory)][ValidateSet('Start','Stop','Status')][string]$Action,
    [ValidateSet('all','mysql','redis','epmd','rabbitmq','nacos','product-a','product-b','marketing','order','gateway')][string]$Node='all',
    [ValidatePattern('^[a-z0-9-]{1,36}$')][string]$RunId='default',
    [switch]$CleanData,
    [switch]$Drill
)
$ErrorActionPreference='Stop'
chcp 65001 > $null
[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new()
$OutputEncoding=[Console]::OutputEncoding
$root=Split-Path $PSScriptRoot -Parent
$runtime=Join-Path $root "runtime/lab-$RunId"
$tools=Join-Path (Split-Path $root -Parent) 'limited-trade-platform/tools'
$java=Join-Path ($env:LAB_JAVA_HOME ?? 'C:/Program Files/Java/jdk-21.0.10') 'bin/java.exe'
$mysqlBin=$env:LAB_MYSQL_BIN ?? 'C:/Program Files/MySQL/MySQL Server 8.0/bin'
$mysqlServer=Join-Path $mysqlBin 'mysqld.exe'
$mysql=Join-Path $mysqlBin 'mysql.exe'
$nacosHome=$env:LAB_NACOS_HOME ?? (Join-Path $root 'runtime/nacos-3.0.3/nacos')
$nacosJar=Join-Path $nacosHome 'target/nacos-server.jar'
$rabbitStart=Join-Path ($env:LAB_RABBITMQ_HOME ?? (Join-Path $tools 'rabbitmq_server-4.3.6')) 'sbin/rabbitmq-server.bat'
$erlangHome=$env:LAB_ERLANG_HOME ?? (Join-Path $tools 'Erlang OTP')
$epmd=$env:LAB_EPMD_EXE
if(!$epmd -and (Test-Path -LiteralPath $erlangHome)){
    $epmd=Get-ChildItem -LiteralPath $erlangHome -Filter 'erts-*' -Directory | ForEach-Object {
        Join-Path $_.FullName 'bin/epmd.exe'
    } | Where-Object {Test-Path -LiteralPath $_ -PathType Leaf} | Select-Object -First 1
}
$wsl=Join-Path $env:SystemRoot 'System32/wsl.exe'
$distribution=$env:LAB_WSL_DISTRIBUTION ?? 'Ubuntu-22.04'
$loopback='127.0.0.1'
$nodes=[ordered]@{
    mysql=@{Port=18762}
    redis=@{Port=18763}
    epmd=@{Port=18766}
    rabbitmq=@{Port=18764;Extra=@(18765)}
    nacos=@{Port=18760;Extra=@(18759,19760,19761)}
    'product-a'=@{Port=18770;Module='product-stock-service'}
    'product-b'=@{Port=18771;Module='product-stock-service'}
    marketing=@{Port=18772;Module='marketing-service'}
    order=@{Port=18780;Module='order-service'}
    gateway=@{Port=18800;Module='gateway'}
}
$contextPath=Join-Path $runtime 'connection.json'
$context=if(Test-Path -LiteralPath $contextPath){Get-Content -Encoding UTF8 -LiteralPath $contextPath -Raw | ConvertFrom-Json}
$names=@(if($Node -eq 'all'){$nodes.Keys}else{$Node})

function Quote-Argument([string]$Value){
    if($Value.Contains([char]34)){throw '启动参数不能含双引号'}
    [string]::Concat([char]34,$Value,[char]34)
}

function Get-OwnedProcess([string]$Name){
    $path=Join-Path $runtime "$Name.process.json"
    if(!(Test-Path -LiteralPath $path)){return}
    $record=Get-Content -Encoding UTF8 -LiteralPath $path -Raw | ConvertFrom-Json
    $processId=[int]$record.processId
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
    if(!$process){return}
    # 同时检查可执行文件、命令行和创建时间，防止误停复用PID的其他进程。
    if($process.ExecutablePath -ne $record.executable -or $process.CommandLine -cne $record.commandLine -or
        $process.CreationDate.ToUniversalTime().Ticks -ne ([datetime]$record.creationTime).ToUniversalTime().Ticks){
        throw "进程归属不符，拒绝操作：$Name"
    }
    $process
}

function Start-OwnedProcess([string]$Name,[string]$Executable,[string[]]$Arguments,[hashtable]$Environment=@{}){
    if(!(Test-Path -LiteralPath $Executable -PathType Leaf)){throw "缺少可执行文件：$Executable"}
    $stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $stdout=Join-Path $runtime "$Name-$stamp.out.log"
    $stderr=Join-Path $runtime "$Name-$stamp.err.log"
    $launch=@{FilePath=$Executable;ArgumentList=$Arguments;WorkingDirectory=$runtime;Environment=$Environment;
        WindowStyle='Hidden';RedirectStandardOutput=$stdout;RedirectStandardError=$stderr;PassThru=$true}
    $child=Start-Process @launch
    try{
        $info=Get-CimInstance Win32_Process -Filter "ProcessId=$($child.Id)"
        if(!$info){throw "$Name 启动退出，请检查 $stderr"}
        [pscustomobject]@{processId=$child.Id;executable=$info.ExecutablePath;commandLine=$info.CommandLine;
            creationTime=$info.CreationDate.ToUniversalTime().ToString('o');stdout=$stdout;stderr=$stderr} |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime "$Name.process.json") -Encoding utf8
    }catch{
        # 清理尚未来得及写入manifest、但本调用已直接持有的子进程。
        if(!$child.HasExited){
            if($Name -eq 'rabbitmq'){& taskkill.exe /PID $child.Id /T /F | Out-Null}
            elseif($Name -eq 'redis'){
                & $wsl -d $context.distribution -- env "REDISCLI_AUTH=$($context.redisPassword)" redis-cli -h 127.0.0.1 -p $nodes.redis.Port SHUTDOWN NOSAVE 2>$null | Out-Null
                if(!$child.HasExited){Stop-Process -InputObject $child}
            }else{Stop-Process -InputObject $child}
        }
        throw
    }
    $child
}

function Wait-LabReady([string]$Name,[int]$Timeout,[scriptblock]$Probe){
    $deadline=(Get-Date).AddSeconds($Timeout)
    while((Get-Date) -lt $deadline){
        if(!(Get-OwnedProcess $Name)){throw "$Name 启动已退出，请检查 $runtime 中的日志"}
        if(& $Probe){return}
        Start-Sleep -Milliseconds 350
    }
    throw "$Name 就绪超时，请检查 $runtime 中的日志"
}

function Test-LabHttp([string]$Url){
    try{return (Invoke-WebRequest -Uri $Url -TimeoutSec 2 -NoProxy -SkipHttpErrorCheck).StatusCode -eq 200}
    catch [System.Net.Http.HttpRequestException]{return $false}
    catch [System.Threading.Tasks.TaskCanceledException]{return $false}
}

function Invoke-LabMysql([string]$Sql,[string]$User=$context.dbUser){
    $previous=$env:MYSQL_PWD
    $env:MYSQL_PWD=$context.dbPassword
    try{
        $args=@('--no-defaults','--protocol=TCP',"--host=$loopback","--port=$($nodes.mysql.Port)",
            "--user=$User",'--connect-timeout=3','--default-character-set=utf8mb4','--batch','--raw','--skip-column-names')
        if($User -ne 'root'){$args+='cloud_mall_lab'}
        $Sql | & $mysql @args
    }finally{$env:MYSQL_PWD=$previous}
}

function Stop-LabNode([string]$Name){
    $owned=Get-OwnedProcess $Name
    if($owned){
        $target=Get-Process -Id $owned.ProcessId -ErrorAction SilentlyContinue
        if($Name -eq 'redis'){
            # 使用本次运行的专用密码关闭 Redis，不终止发行版。
            $reply=& $wsl -d $context.distribution -- env "REDISCLI_AUTH=$($context.redisPassword)" redis-cli -h 127.0.0.1 -p $nodes.redis.Port SHUTDOWN NOSAVE 2>&1
            if($LASTEXITCODE -ne 0){throw "隔离 Redis 关闭失败：退出码 $LASTEXITCODE"}
        }elseif($Name -eq 'mysql'){
            $listeners=@(Get-NetTCPConnection -State Listen -LocalAddress $loopback -LocalPort $nodes.mysql.Port -ErrorAction SilentlyContinue)
            if($listeners){
                if($listeners.Count -ne 1 -or $listeners[0].OwningProcess -ne $owned.ProcessId){
                    throw 'MySQL 监听进程与本次manifest不符，拒绝关闭'
                }
                $databasePath=Invoke-LabMysql 'SELECT @@datadir' 'root'
                if($LASTEXITCODE -ne 0 -or !$databasePath -or
                    (Resolve-Path -LiteralPath $databasePath.TrimEnd([char[]]'\/')).Path -ne (Join-Path $runtime 'mysql-data')){
                    throw 'MySQL 数据目录与本次实验不符，拒绝关闭'
                }
                Invoke-LabMysql 'SHUTDOWN' 'root'
                if($LASTEXITCODE -ne 0){throw '隔离 MySQL 没有确认正常关库'}
            }elseif($target){Stop-Process -InputObject $target}
        }elseif($target -and $Name -eq 'rabbitmq'){
            & taskkill.exe /PID $owned.ProcessId /T /F | Out-Null
            if($LASTEXITCODE -ne 0){throw 'RabbitMQ 实验进程树停止失败'}
        }elseif($target){Stop-Process -InputObject $target}
        if($target -and !$target.WaitForExit(15000)){throw "停止超时：$Name"}
    }
    if($Name -eq 'mysql' -and (Test-Path -LiteralPath (Join-Path $runtime 'mysql-data'))){
        $deadline=(Get-Date).AddSeconds(8)
        while(Get-NetTCPConnection -State Listen -LocalPort $nodes.mysql.Port -ErrorAction SilentlyContinue){
            if((Get-Date) -ge $deadline){throw 'MySQL 端口仍在监听，保留本次运行的数据目录'}
            Start-Sleep -Milliseconds 300
        }
    }
    $path=Join-Path $runtime "$Name.process.json"
    if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path}
    Write-Output "Stopped $Name"
}

function Start-LabNode([string]$Name){
    $spec=$nodes[$Name]
    switch($Name){
        mysql {
            $data=Join-Path $runtime 'mysql-data'
            if(!(Test-Path -LiteralPath (Join-Path $data 'mysql'))){
                $args=@('--no-defaults','--initialize-insecure',(Quote-Argument "--datadir=$data"))
                $launch=@{FilePath=$mysqlServer;ArgumentList=$args;WorkingDirectory=$runtime;WindowStyle='Hidden';
                    RedirectStandardOutput=(Join-Path $runtime 'mysql-init.out.log');
                    RedirectStandardError=(Join-Path $runtime 'mysql-init.err.log');Wait=$true;PassThru=$true}
                if((Start-Process @launch).ExitCode -ne 0){throw 'MySQL 初始化失败，请检查 mysql-init.err.log'}
            }
            $init=Join-Path $runtime 'mysql-init.sql'
            $first=!(Test-Path -LiteralPath (Join-Path $runtime 'mysql-initialized'))
            $args=@('--no-defaults','--no-monitor',(Quote-Argument "--datadir=$data"),"--bind-address=$loopback",
                "--port=$($spec.Port)",'--mysqlx=OFF','--skip-log-bin','--innodb-buffer-pool-size=128M')
            if($first){
                @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$($context.dbPassword)';
CREATE DATABASE cloud_mall_lab CHARACTER SET utf8mb4;
CREATE USER '$($context.dbUser)'@'localhost' IDENTIFIED BY '$($context.dbPassword)';
GRANT ALL ON cloud_mall_lab.* TO '$($context.dbUser)'@'localhost';
"@ | Set-Content -LiteralPath $init -Encoding utf8
                $args+=Quote-Argument "--init-file=$init"
            }
            try{
                $child=Start-OwnedProcess $Name $mysqlServer $args
                Wait-LabReady $Name 45 { $reply=Invoke-LabMysql 'SELECT 1' 2>$null; $LASTEXITCODE -eq 0 -and $reply -eq '1' }
                if($first){
                    $files=@(Get-ChildItem -LiteralPath (Join-Path $root 'sql') -Filter '*.sql' -File | Sort-Object Name)
                    if(!$files){throw '本工程 sql 目录中缺少初始化文件'}
                    $ddl=($files | ForEach-Object {Get-Content -Encoding UTF8 -LiteralPath $_.FullName -Raw}) -join [Environment]::NewLine
                    Invoke-LabMysql $ddl
                    if($LASTEXITCODE -ne 0){throw '隔离库初始化 SQL 失败'}
                    New-Item -ItemType File -Path (Join-Path $runtime 'mysql-initialized') | Out-Null
                }
            }finally{
                if(Test-Path -LiteralPath $init){Remove-Item -LiteralPath $init}
            }
        }
        redis {
            $existing=& $wsl -d $context.distribution -- ss -H -ltn "sport = :$($spec.Port)" 2>$null
            if($LASTEXITCODE -ne 0){throw "无法检查 WSL 端口：$($context.distribution)"}
            if($existing -match '^\s*LISTEN\s'){throw "WSL Redis 端口已占用：$($spec.Port)"}
            $bind=@($loopback,$context.redisHost | Select-Object -Unique)
            $redisConf=Join-Path $runtime 'redis-local.conf'
            @"
bind $($bind -join ' ')
protected-mode yes
port $($spec.Port)
requirepass $($context.redisPassword)
dir /tmp
save ""
appendonly no
"@ | Set-Content -LiteralPath $redisConf -Encoding utf8
            try {
                $linuxConfig=& $wsl -d $context.distribution -- wslpath -a -u ($redisConf.Replace('\','/')) 2>$null
                if($LASTEXITCODE -ne 0 -or $linuxConfig -notmatch '^/\S+$'){throw '无法定位 WSL 中的实验 Redis 配置'}
                # wsl.exe 直接解析 -d，带多余引号会将引号视为分发名称的一部分。
                if($context.distribution -match '\s'){throw 'LAB_WSL_DISTRIBUTION 应使用不含空格的分发名称'}
                $child=Start-OwnedProcess $Name $wsl @('-d',$context.distribution,'--','redis-server',$linuxConfig)
                Wait-LabReady $Name 30 {
                    $reply=& $wsl -d $context.distribution -- env "REDISCLI_AUTH=$($context.redisPassword)" redis-cli -h 127.0.0.1 -p $spec.Port PING 2>$null
                    $LASTEXITCODE -eq 0 -and $reply -eq 'PONG'
                }
            } finally { Remove-Item -LiteralPath $redisConf }
        }
        epmd {
            if(!$epmd){throw "缺少 Erlang EPMD：$erlangHome"}
            $child=Start-OwnedProcess $Name $epmd @('-address',$loopback,'-port',"$($spec.Port)")
            Wait-LabReady $Name 10 { [bool](Get-NetTCPConnection -State Listen -LocalPort $spec.Port -ErrorAction SilentlyContinue) }
        }
        rabbitmq {
            $base=Join-Path $runtime 'rabbitmq-data'
            New-Item -ItemType Directory -Path $base -Force | Out-Null
            $conf=Join-Path $base 'rabbitmq'
            $plugins=Join-Path $base 'enabled_plugins'
            @"
listeners.tcp.local = $($loopback):$($spec.Port)
distribution.listener.port_range.min = $($spec.Extra[0])
distribution.listener.port_range.max = $($spec.Extra[0])
default_user = $($context.mqUser)
default_pass = $($context.mqPassword)
"@ | Set-Content -LiteralPath "$conf.conf" -Encoding utf8
            '[].' | Set-Content -LiteralPath $plugins -Encoding utf8
            $rabbitEnv=@{ERLANG_HOME=$erlangHome;RABBITMQ_BASE=$base;RABBITMQ_CONFIG_FILE=$conf;
                RABBITMQ_ENABLED_PLUGINS_FILE=$plugins;RABBITMQ_NODENAME="cloud_lab_$($RunId.Replace('-','_'))@localhost";
                ERL_EPMD_ADDRESS=$loopback;ERL_EPMD_PORT="$($nodes.epmd.Port)";
                RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS='+S 4:4 -kernel inet_dist_use_interface {127,0,0,1}'}
            if(!(Test-Path -LiteralPath $rabbitStart -PathType Leaf)){throw "缺少便携 RabbitMQ：$rabbitStart"}
            $batArgument=[string]::Concat([char]34,[char]34,$rabbitStart,[char]34,[char]34)
            $child=Start-OwnedProcess $Name $env:ComSpec @('/d','/s','/c',$batArgument) $rabbitEnv
            Wait-LabReady $Name 60 { [bool](Get-NetTCPConnection -State Listen -LocalPort $spec.Port -ErrorAction SilentlyContinue) }
        }
        nacos {
            $homePath=Join-Path $runtime 'nacos-data'
            $config=Join-Path $homePath 'conf'
            New-Item -ItemType Directory -Path $config -Force | Out-Null
            Get-ChildItem -LiteralPath (Join-Path $nacosHome 'conf') -File | Copy-Item -Destination $config
            $uri=([uri]("$config/")).AbsoluteUri
            $args=@('-Xms128m','-Xmx512m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8','-Dnacos.standalone=true',
                '-Dnacos.inetutils.ip-address=127.0.0.1',(Quote-Argument "-Dnacos.home=$homePath"),
                '-jar',(Quote-Argument $nacosJar),"--nacos.server.main.port=$($spec.Port)","--server.address=$loopback",
                "--nacos.console.port=$($spec.Extra[0])","--nacos.console.address=$loopback",
                "--nacos.remote.grpc.listen.ip=$loopback",
                '--nacos.core.auth.enabled=false','--nacos.core.auth.admin.enabled=false','--nacos.core.auth.console.enabled=false',
                (Quote-Argument "--spring.config.additional-location=$uri"),
                (Quote-Argument "--logging.config=$config/nacos-logback.xml"))
            $child=Start-OwnedProcess $Name $java $args
            Wait-LabReady $Name 90 { Test-LabHttp "http://$($loopback):$($spec.Port)/nacos/v3/admin/core/state" }
            $properties=Get-Content -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot 'nacos-defaults.properties') -Raw
            $body=@{dataId='cloud-lab.properties';groupName='DEFAULT_GROUP';content=$properties;type='properties'}
            $published=Invoke-RestMethod -Uri "http://$($loopback):$($spec.Port)/nacos/v3/admin/cs/config" -Method POST -Body $body -NoProxy -TimeoutSec 6
            if($published.code -ne 0 -or !$published.data){throw 'Nacos 初始动态配置发布失败'}
        }
        default {
            $jar=Join-Path $root "$($spec.Module)/target/$($spec.Module)-0.0.1-SNAPSHOT.jar"
            if(!(Test-Path -LiteralPath $jar -PathType Leaf)){throw "缺少构建产物：$jar"}
            $serviceEnv=@{LAB_NACOS_SERVER="$($loopback):$($nodes.nacos.Port)";
                LAB_DB_URL="jdbc:mysql://$($loopback):$($nodes.mysql.Port)/cloud_mall_lab?allowPublicKeyRetrieval=true&sslMode=DISABLED&serverTimezone=Asia/Shanghai";
                LAB_DB_USER=$context.dbUser;LAB_DB_PASSWORD=$context.dbPassword;
                LAB_REDIS_HOST=$context.redisHost;LAB_REDIS_PORT="$($nodes.redis.Port)";LAB_REDIS_PASSWORD=$context.redisPassword;
                LAB_MQ_HOST=$loopback;LAB_MQ_PORT="$($nodes.rabbitmq.Port)";LAB_MQ_USER=$context.mqUser;LAB_MQ_PASSWORD=$context.mqPassword}
            $args=@('-Xmx320m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8',
                '-Dsun.stderr.encoding=UTF-8','-jar',(Quote-Argument $jar),"--server.address=$loopback",
                "--server.port=$($spec.Port)","--lab.instance=$Name")
            if($Drill -and $Name -in @('product-a','product-b')){
                # 拉长数据库兜底扫描间隔，验收用消费日志证明由真实MQ触发回补。
                $args+='--lab.stock.recovery-interval-ms=300000'
            }
            $child=Start-OwnedProcess $Name $java $args $serviceEnv
            Wait-LabReady $Name 75 { Test-LabHttp "http://$($loopback):$($spec.Port)/actuator/health" }
        }
    }
    Write-Output "Started $Name on $($spec.Port) PID=$($child.Id)"
}

if($Action -eq 'Status'){
    foreach($name in $names){
        $owned=Get-OwnedProcess $name
        [pscustomobject]@{Node=$name;Running=[bool]$owned;ProcessId=$owned.ProcessId;Port=$nodes[$name].Port}
    }
    return
}
if($Action -eq 'Stop'){
    [array]::Reverse($names)
    $failures=@()
    foreach($name in $names){try{Stop-LabNode $name}catch{$failures+=$_.Exception.Message}}
    if($failures){throw ($failures -join [Environment]::NewLine)}
    if($CleanData -and $Node -eq 'all' -and (Test-Path -LiteralPath $runtime)){
        $resolved=(Resolve-Path -LiteralPath $runtime).Path
        $runtimeRoot=(Resolve-Path -LiteralPath (Join-Path $root 'runtime')).Path
        if($resolved -ne (Join-Path $runtimeRoot "lab-$RunId") -or
            (Get-Item -LiteralPath $runtimeRoot).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint) -or
            (Get-Item -LiteralPath $resolved).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)){
            throw '只清理本工程 runtime 下本次运行的普通目录'
        }
        foreach($name in @('mysql-data','rabbitmq-data','nacos-data')){
            $path=Join-Path $resolved $name
            if(!(Test-Path -LiteralPath $path)){continue}
            $target=(Resolve-Path -LiteralPath $path).Path
            if($target -ne $path -or (Get-Item -LiteralPath $target).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint) -or
                (Get-ChildItem -LiteralPath $target -Attributes ReparsePoint -Recurse -Force)){
                throw "拒绝清理含重解析点的路径：$target"
            }
            Remove-Item -LiteralPath $target -Recurse -Force
        }
        foreach($name in @('connection.json','mysql-initialized','mysql-init.sql')){
            $path=Join-Path $resolved $name
            if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path}
        }
    }
    return
}
foreach($name in $names){
    if(Get-OwnedProcess $name){throw "已有运行节点 $name，请先明确停止"}
    foreach($port in @($nodes[$name].Port)+$nodes[$name].Extra){
        if($port -and (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)){throw "端口已占用：$port"}
    }
}
if(!$context -and $Node -eq 'nacos'){
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
}elseif(!$context){
    if($Node -ne 'all'){throw '首次启动请使用 -Node all，以初始化独立中间件'}
    $addresses=& $wsl -d $distribution -- hostname -I 2>&1
    if($LASTEXITCODE -ne 0){throw "无法访问 WSL 发行版 $distribution，请在有权限的终端启动"}
    $redisHost=([string]$addresses -split '\s+' | Where-Object {$_ -match '^\d+\.\d+\.\d+\.\d+$'} | Select-Object -First 1)
    if(!$redisHost){throw 'WSL Redis 缺少可连接的 IPv4 地址'}
    $context=[pscustomobject]@{mysql=$mysql;dbUser='lab_user';dbPassword=[guid]::NewGuid().ToString('N');
        distribution=$distribution;redisHost=$redisHost;redisPassword=[guid]::NewGuid().ToString('N');
        mqUser='lab_mq';mqPassword=[guid]::NewGuid().ToString('N')}
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    $context | ConvertTo-Json | Set-Content -LiteralPath $contextPath -Encoding utf8
}
$started=[System.Collections.Generic.List[string]]::new()
try{
    foreach($name in $names){$started.Add($name); Start-LabNode $name}
}catch{
    $launchError=$_
    $started.Reverse()
    foreach($name in $started){try{Stop-LabNode $name}catch{Write-Warning $_.Exception.Message}}
    throw $launchError
}
