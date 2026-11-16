# Sobe os 3 servidores (sp, rj, ba) como processos locais, sem Docker.
# Plano B da apresentacao: os scripts de demo funcionam igual, nas mesmas portas.
#
#   .\scripts\local-5nos.ps1            # sobe
#   .\scripts\local-5nos.ps1 -Parar     # encerra

param([switch]$Parar)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $raiz 'target\minidynamo.jar'

# Logs e pid ficam fora de target/: 'mvn clean' apaga target.
$logs = Join-Path $raiz 'logs'
$pidFile = Join-Path $logs 'nos-locais.pid'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

if ($Parar) {
    if (Test-Path $pidFile) {
        Get-Content $pidFile | ForEach-Object {
            try { Stop-Process -Id ([int]$_) -Force -ErrorAction Stop; "servidor encerrado (pid $_)" }
            catch { "pid $_ ja nao estava rodando" }
        }
        Remove-Item $pidFile -Force
    } else {
        'nenhum pid registrado; nada a parar'
    }
    return
}

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Users\luiz_\tools\jdk-21.0.13+9' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $jar)) { throw "jar nao encontrado em $jar -- rode: mvn -B -DskipTests package" }

$servidores = @(
    @{ id = 'sp'; porta = 8081 }
    @{ id = 'rj'; porta = 8082 }
    @{ id = 'ba'; porta = 8083 }
)
$peers = ($servidores | ForEach-Object { "$($_.id)@127.0.0.1:$($_.porta)" }) -join ','

$pids = @()
foreach ($s in $servidores) {
    $env:NODE_ID = $s.id
    $env:HTTP_PORT = "$($s.porta)"
    $env:PEERS = $peers
    $env:REPLICATION_N = '2'
    $env:DEFAULT_R = '2'
    $env:DEFAULT_W = '2'
    $env:VNODES = '128'

    $p = Start-Process -FilePath $java -ArgumentList '-Xmx128m', '-jar', $jar `
        -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs "$($s.id).log") `
        -RedirectStandardError  (Join-Path $logs "$($s.id).err")
    $pids += $p.Id
    "$($s.id)  ->  http://localhost:$($s.porta)   (pid $($p.Id))"
}
$pids | Set-Content $pidFile

Start-Sleep -Seconds 2
''
foreach ($s in $servidores) {
    try {
        $r = Invoke-RestMethod "http://localhost:$($s.porta)/health" -TimeoutSec 5
        "  $($s.id)  OK"
    } catch {
        "  $($s.id)  FALHOU -- veja logs\$($s.id).err"
    }
}
''
"para encerrar:  .\scripts\local-5nos.ps1 -Parar"
