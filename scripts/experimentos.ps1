# Matriz de experimentos com 3 servidores (sp, rj, ba) e N=2.
#
# Cada configuracao e repetida e a ordem alterna entre repeticoes. Uma rodada
# de aquecimento e descartada. Com -LatenciaMs, injeta atraso entre servidores,
# para que a janela de replicacao seja observavel.
#
#   .\scripts\experimentos.ps1 -Repeticoes 3 -Ops 300 -Chaves 30 -LatenciaMs 120 -JitterMs 40

param(
    [int]$Repeticoes = 3,
    [int]$Ops = 400,
    [int]$Chaves = 40,
    [int]$Concorrencia = 16,
    [int]$LatenciaMs = 0,
    [int]$JitterMs = 0,
    [string]$Csv = 'resultados/resultados.csv'
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $raiz 'target\minidynamo.jar'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Users\luiz_\tools\jdk-21.0.13+9' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $jar)) { throw "jar nao encontrado em $jar -- rode: mvn -B -DskipTests package" }

$portas = @(8081, 8082, 8083)

# N=2 com 3 servidores: R+W > N com R=W=2 ou R=W=1 em eventual/causal.
$configs = @(
    @{ rotulo = 'R1W1-eventual'; modo = 'eventual'; r = 1; w = 1 }
    @{ rotulo = 'R2W2-forte';    modo = 'forte';    r = 2; w = 2 }
    @{ rotulo = 'R1W1-causal';   modo = 'causal';   r = 1; w = 1 }
)

function Rodar($cfg, $rotulo, $csv) {
    & $java -cp $jar sd.minidynamo.bench.Bench `
        "--modo=$($cfg.modo)" "--r=$($cfg.r)" "--w=$($cfg.w)" `
        "--rotulo=$rotulo" "--ops=$Ops" "--chaves=$Chaves" `
        "--concorrencia=$Concorrencia" "--csv=$csv"
}

function AplicarLatencia([int]$ms, [int]$jitter) {
    foreach ($p in $portas) {
        Invoke-RestMethod -Method Post -Uri "http://localhost:$p/chaos/latencia" `
            -Body (@{ ms = $ms; jitterMs = $jitter } | ConvertTo-Json -Compress) `
            -ContentType 'application/json' -TimeoutSec 10 | Out-Null
    }
}

function CurarTudo {
    foreach ($p in $portas) {
        try { Invoke-RestMethod -Method Post -Uri "http://localhost:$p/chaos/curar" -TimeoutSec 10 | Out-Null } catch { }
    }
}

Push-Location $raiz
try {
    $sufixo = ''
    if ($LatenciaMs -gt 0) {
        Write-Host ''
        Write-Host "### latencia de ${LatenciaMs}ms (jitter ${JitterMs}ms) entre servidores" -ForegroundColor Yellow
        AplicarLatencia $LatenciaMs $JitterMs
        $sufixo = "-lat$LatenciaMs"
    } else {
        CurarTudo
    }

    Write-Host ''
    Write-Host '### aquecimento (descartado)' -ForegroundColor DarkGray
    $descartavel = 'resultados/_aquecimento.csv'
    foreach ($cfg in $configs) { Rodar $cfg "aquecimento-$($cfg.rotulo)" $descartavel | Out-Null }
    if (Test-Path $descartavel) { Remove-Item $descartavel -Force }

    for ($i = 1; $i -le $Repeticoes; $i++) {
        Write-Host ''
        Write-Host "### repeticao $i de $Repeticoes" -ForegroundColor Cyan
        $ordem = if ($i % 2 -eq 0) { $configs[($configs.Count - 1)..0] } else { $configs }
        foreach ($cfg in $ordem) {
            Rodar $cfg "$($cfg.rotulo)$sufixo-rep$i" $Csv |
                Select-String -Pattern 'resultado \[|OBSOLET|escrita  p50|leitura  p50|vazao'
        }
    }

    if ($LatenciaMs -gt 0) {
        CurarTudo
        Write-Host ''
        Write-Host '### latencia removida' -ForegroundColor Yellow
    }
    Write-Host ''
    Write-Host 'pronto.' -ForegroundColor Green
}
finally { Pop-Location }
