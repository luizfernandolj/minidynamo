# Matriz de experimentos para o relatorio.
#
# Diferencas em relacao a rodar o bench na mao, que e o que torna o numero
# publicavel:
#   - rodada de aquecimento descartada (JIT da JVM, cache de conexao HTTP);
#   - cada configuracao repetida N vezes;
#   - ordem das configuracoes alternada entre repeticoes, para que a primeira
#     config nao pague sozinha o custo de aquecimento residual.
#
#   .\scripts\experimentos.ps1                       # 3 repeticoes
#   .\scripts\experimentos.ps1 -Repeticoes 5 -Ops 800

param(
    [int]$Repeticoes = 3,
    [int]$Ops = 400,
    [int]$Chaves = 40,
    [int]$Concorrencia = 16,
    [string]$Csv = 'resultados/resultados.csv'
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $raiz 'target\minidynamo.jar'

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Users\luiz_\tools\jdk-21.0.13+9' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'

if (-not (Test-Path $jar)) {
    throw "jar nao encontrado em $jar -- rode primeiro: mvn -B -DskipTests package"
}

$configs = @(
    @{ rotulo = 'R1W1-eventual'; modo = 'eventual'; r = 1; w = 1 }
    @{ rotulo = 'R2W2-forte';    modo = 'forte';    r = 2; w = 2 }
    @{ rotulo = 'R3W3-forte';    modo = 'forte';    r = 3; w = 3 }
    @{ rotulo = 'R1W1-causal';   modo = 'causal';   r = 1; w = 1 }
)

function Rodar($cfg, $rotulo, $csv) {
    & $java -cp $jar sd.minidynamo.bench.Bench `
        "--modo=$($cfg.modo)" "--r=$($cfg.r)" "--w=$($cfg.w)" `
        "--rotulo=$rotulo" "--ops=$Ops" "--chaves=$Chaves" `
        "--concorrencia=$Concorrencia" "--csv=$csv"
}

Push-Location $raiz
try {
    Write-Host ''
    Write-Host '### aquecimento (resultado descartado)' -ForegroundColor DarkGray
    # Grava num csv temporario que nao entra no relatorio.
    $descartavel = 'resultados/_aquecimento.csv'
    foreach ($cfg in $configs) {
        Rodar $cfg "aquecimento-$($cfg.rotulo)" $descartavel | Out-Null
    }
    if (Test-Path $descartavel) { Remove-Item $descartavel -Force }

    for ($i = 1; $i -le $Repeticoes; $i++) {
        Write-Host ''
        Write-Host "### repeticao $i de $Repeticoes" -ForegroundColor Cyan

        # Alterna a ordem a cada repeticao par.
        $ordem = if ($i % 2 -eq 0) { $configs[($configs.Count - 1)..0] } else { $configs }

        foreach ($cfg in $ordem) {
            Rodar $cfg "$($cfg.rotulo)-rep$i" $Csv |
                Select-String -Pattern 'resultado \[|OBSOLET|escrita  p50|leitura  p50|vazao'
        }
    }

    Write-Host ''
    Write-Host "pronto. agregue com:" -ForegroundColor Green
    Write-Host "  Import-Csv $Csv | Group-Object modo,r,w | ForEach-Object { `$_.Name + ' -> obsoletas ' + ([math]::Round((`$_.Group | Measure-Object pct_obsoletas -Average).Average,2)) + '%' }"
} finally {
    Pop-Location
}
