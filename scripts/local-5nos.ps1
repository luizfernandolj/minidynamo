# Sobe 5 nos como processos locais, sem Docker.
#
# Serve para desenvolver antes de o Docker estar disponivel e para depurar com
# o debugger da IDE acoplado. O enunciado aceita "3 nos/processos comunicando-se
# via rede", e e o caso aqui -- mas a entrega final deve usar docker compose.
#
#   .\scripts\local-5nos.ps1            # sobe
#   .\scripts\local-5nos.ps1 -Parar     # mata todos

param([switch]$Parar)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $raiz 'target\minidynamo.jar'

# Logs e pid ficam FORA de target/: 'mvn clean' apaga target, e com os nos no ar
# o clean falha por nao conseguir deletar um log aberto -- alem de levar embora
# o arquivo de pid, deixando processos orfaos.
$logs = Join-Path $raiz 'logs'
$pidFile = Join-Path $logs 'nos-locais.pid'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

if ($Parar) {
    if (Test-Path $pidFile) {
        Get-Content $pidFile | ForEach-Object {
            $processId = [int]$_
            try {
                Stop-Process -Id $processId -Force -ErrorAction Stop
                "no encerrado (pid $processId)"
            } catch {
                "pid $processId ja nao estava rodando"
            }
        }
        Remove-Item $pidFile -Force
    } else {
        'nenhum arquivo de pid encontrado; nada a parar'
    }
    return
}

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Users\luiz_\tools\jdk-21.0.13+9' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'

if (-not (Test-Path $jar)) {
    throw "jar nao encontrado em $jar -- rode primeiro: mvn -B -DskipTests package"
}

# Todos os nos recebem a mesma string PEERS. Portas 8081..8085.
$peers = (1..5 | ForEach-Object { "node$_@127.0.0.1:808$_" }) -join ','

$pids = @()
foreach ($i in 1..5) {
    $env:NODE_ID = "node$i"
    $env:HTTP_PORT = "808$i"
    $env:PEERS = $peers
    $env:REPLICATION_N = '3'
    $env:VNODES = '128'

    $p = Start-Process -FilePath $java `
        -ArgumentList '-Xmx128m', '-jar', $jar `
        -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs "node$i.log") `
        -RedirectStandardError  (Join-Path $logs "node$i.err")

    $pids += $p.Id
    "node$i  ->  http://localhost:808$i   (pid $($p.Id))"
}
$pids | Set-Content $pidFile

Start-Sleep -Seconds 2
''
'verificando /health de cada no:'
foreach ($i in 1..5) {
    try {
        $r = Invoke-RestMethod "http://localhost:808$i/health" -TimeoutSec 5
        "  node$i  OK   chaves=$($r.chaveslocais)"
    } catch {
        "  node$i  FALHOU  -- veja logs\node$i.err"
    }
}
''
"para parar:  .\scripts\local-5nos.ps1 -Parar"
