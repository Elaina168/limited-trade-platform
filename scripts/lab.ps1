param(
    [Parameter(Mandatory)][ValidateSet('Start','Stop','Status')][string]$Action,
    [ValidateSet('all','registry','config-server','product-a','product-b','preview','gateway-a','gateway-b')][string]$Node='all',
    [switch]$Drill,
    [int]$TotalLimit=8,
    [int]$IpLimit=4,
    [string]$LimitPeriod='1s'
)
$ErrorActionPreference='Stop'
chcp 65001 > $null
[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new()
$root=Split-Path $PSScriptRoot -Parent
$java='C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
$runtime=Join-Path $root 'runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$nodes=[ordered]@{
    registry=@{Module='registry';Port=18760}
    'config-server'=@{Module='config-server';Port=18761}
    'product-a'=@{Module='product-service';Port=18770}
    'product-b'=@{Module='product-service';Port=18771}
    preview=@{Module='preview-service';Port=18780}
    'gateway-a'=@{Module='gateway';Port=18800}
    'gateway-b'=@{Module='gateway';Port=18801}
}
$names=if($Node -eq 'all'){@($nodes.Keys)}else{@($Node)}
if($Action -eq 'Stop') { [array]::Reverse($names) }
foreach($name in $names){
    $spec=$nodes[$name]
    $jar=Join-Path $root "$($spec.Module)/target/$($spec.Module)-0.0.1-SNAPSHOT.jar"
    $state=Join-Path $runtime "$name.process.json"
    $record=if(Test-Path -LiteralPath $state){Get-Content -Encoding UTF8 -LiteralPath $state | ConvertFrom-Json}else{$null}
    $owned=$null
    if($record){
        $owned=Get-CimInstance Win32_Process -Filter "ProcessId=$($record.processId)"
        # PID可能被复用：须同时匹配直接JVM路径、完整JAR路径及创建时间。
        if($owned -and ($owned.ExecutablePath -ne $java -or !$owned.CommandLine.Contains($jar) -or
            $owned.CreationDate.ToUniversalTime().Ticks -ne ([datetime]$record.creationTime).ToUniversalTime().Ticks)){
            throw "进程归属不符，拒绝操作：$name"
        }
    }
    if($Action -eq 'Status'){
        [pscustomobject]@{Node=$name;Running=[bool]$owned;ProcessId=if($owned){$owned.ProcessId}else{$null};Port=$spec.Port}
        continue
    }
    if($Action -eq 'Stop'){
        if($owned){
            $target=Get-Process -Id $owned.ProcessId
            Stop-Process -InputObject $target
            if(!$target.WaitForExit(15000)){throw "停止超时：$name"}
        }
        if(Test-Path -LiteralPath $state){Remove-Item -LiteralPath $state}
        Write-Output "Stopped $name"
        continue
    }
    if($owned){throw "本工程节点已运行：$name，先明确停止后再启动"}
    if(Get-NetTCPConnection -State Listen -LocalPort $spec.Port -ErrorAction SilentlyContinue){throw "端口已占用：$($spec.Port)"}
    if(!(Test-Path -LiteralPath $jar)){throw "缺少构建产物：$jar"}
    $stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $stdout=Join-Path $runtime "$name-$stamp.out.log"
    $stderr=Join-Path $runtime "$name-$stamp.err.log"
    $arguments=@('-Xmx256m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8',
        '-jar',"`"$jar`"","--server.port=$($spec.Port)","--lab.instance=$name")
    if($Drill -and $name -in @('product-a','product-b','preview')){$arguments+='--spring.profiles.active=drill'}
    if($spec.Module -eq 'gateway'){$arguments+=@("--lab.limit.total=$TotalLimit","--lab.limit.per-ip=$IpLimit","--lab.limit.period=$LimitPeriod")}
    $launched=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $processInfo=Get-CimInstance Win32_Process -Filter "ProcessId=$($launched.Id)"
    [pscustomobject]@{processId=$launched.Id;jar=$jar;creationTime=$processInfo.CreationDate.ToUniversalTime().ToString('o');stdout=$stdout;stderr=$stderr} |
        ConvertTo-Json | Set-Content -LiteralPath $state -Encoding utf8
    $url=if($name -eq 'registry'){"http://127.0.0.1:$($spec.Port)/eureka/apps"}else{"http://127.0.0.1:$($spec.Port)/actuator/health"}
    # Config Server不依赖Actuator，以配置读取接口验证就绪。
    if($name -eq 'config-server'){$url="http://127.0.0.1:$($spec.Port)/preview-service/default"}
    $deadline=(Get-Date).AddSeconds(75)
    $ready=$false
    while((Get-Date) -lt $deadline){
        if($launched.HasExited){throw "$name 启动退出，请检查 $stderr 和 $stdout"}
        try{
            $response=Invoke-WebRequest -Uri $url -TimeoutSec 2 -NoProxy
            if($response.StatusCode -eq 200){$ready=$true;break}
        }catch [System.Net.Http.HttpRequestException]{ }catch [System.Threading.Tasks.TaskCanceledException]{ }
        Start-Sleep -Milliseconds 400
    }
    if(!$ready){throw "$name 就绪超时，请检查 $stdout"}
    $listener=Get-NetTCPConnection -State Listen -LocalPort $spec.Port
    if($listener.OwningProcess -ne $launched.Id){throw "$name 监听PID不符"}
    Write-Output "Started $name on $($spec.Port) PID=$($launched.Id)"
}
