# Cenario de falha: QUEDA DE NO, com hinted handoff.
#
# Diferente de /chaos/particao: ali o processo continua vivo com seu estado;
# aqui o no morre e volta vazio, porque o armazenamento e em memoria. Os dois
# casos merecem discussao separada no relatorio.
#
# O que este roteiro demonstra:
#   1. com o no fora, W=3 continua fechando -- um substituto guarda a escrita;
#   2. os hints ficam visiveis e contados em /debug/hints;
#   3. quando o no volta, os hints sao entregues e ele recupera o que perdeu;
#   4. mede o tempo entre o no voltar e os hints zerarem.
#
# Requer Docker. Uso:
#   .\scripts\demo-queda-de-no.ps1 -NoAlvo node3

param(
    [string]$NoAlvo = 'node3',
    [string]$BaseUrl = 'http://localhost:808'
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$env:PATH = "C:\Program Files\Docker\Docker\resources\bin;$env:PATH"

function Titulo($texto) {
    ''
    '=' * 72
    "  $texto"
    '=' * 72
}

function Escrever($noIdx, $chave, $valor, $w) {
    $corpo = @{ valor = $valor } | ConvertTo-Json -Compress
    try {
        $r = Invoke-RestMethod -Method Put -Uri "$BaseUrl$noIdx/kv/$chave`?modo=eventual&w=$w" `
            -Body $corpo -ContentType 'application/json' -TimeoutSec 15
        $h = if ($r.hints) { "  hints=[$($r.hints -join '; ')]" } else { '' }
        Write-Host "  W=$w  ESCREVEU  ok=$($r.replicasOk)/$w  sloppy=$($r.sloppy)$h"
        return $r
    } catch {
        $codigo = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        Write-Host "  W=$w  RECUSADO (HTTP $codigo)"
        return $null
    }
}

function HintsNoCluster {
    $total = 0
    $detalhe = @()
    foreach ($i in 1..5) {
        try {
            $h = Invoke-RestMethod "$BaseUrl$i/debug/hints" -TimeoutSec 5
            if ($h.total -gt 0) {
                $total += $h.total
                $porDestino = ($h.pendentesPorDestino.PSObject.Properties |
                    ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ','
                $detalhe += "node$i guarda $($h.total) ($porDestino)"
            }
        } catch { }
    }
    return @{ Total = $total; Detalhe = $detalhe }
}

Push-Location $raiz
try {
    # Escolhe uma chave que tenha o no alvo na lista de preferencia: sem isso o
    # cenario nao prova nada, porque a chave poderia nem morar no no derrubado.
    Titulo "Passo 0 - procurando chave cuja lista de preferencia inclua $NoAlvo"
    $chave = $null
    foreach ($tentativa in 1..300) {
        $candidata = "chave-queda-$tentativa"
        $anel = Invoke-RestMethod "$BaseUrl`1/debug/anel?chave=$candidata" -TimeoutSec 10
        if ($anel.listaDePreferencia -contains $NoAlvo) {
            $chave = $candidata
            "  chave '$chave' -> replicas: $($anel.listaDePreferencia -join ', ')"
            break
        }
    }
    if (-not $chave) { throw "nenhuma chave encontrada com $NoAlvo na lista de preferencia" }

    Titulo 'Passo 1 - cluster completo'
    Escrever 1 $chave 'antes-da-queda' 3 | Out-Null

    Titulo "Passo 2 - derrubando $NoAlvo"
    docker compose stop $NoAlvo | Out-Null
    "  $NoAlvo parado"
    Start-Sleep -Seconds 2

    Titulo 'Passo 3 - escrita com o no fora: substituto assume'
    Escrever 1 $chave 'durante-a-queda' 3 | Out-Null
    '  ^ W=3 fechou mesmo com uma replica fora: o terceiro ack veio de um'
    '    substituto, que guardou a escrita em nome do no derrubado.'
    ''
    $h = HintsNoCluster
    "  hints retidos no cluster: $($h.Total)"
    $h.Detalhe | ForEach-Object { "    $_" }

    Titulo 'Passo 4 - a mesma escrita com sloppy=false'
    $corpo = @{ valor = 'sem-sloppy' } | ConvertTo-Json -Compress
    try {
        $r = Invoke-RestMethod -Method Put -Uri "$BaseUrl`1/kv/$chave`?modo=eventual&w=3&sloppy=false" `
            -Body $corpo -ContentType 'application/json' -TimeoutSec 15
        "  inesperado: aceitou com ok=$($r.replicasOk)"
    } catch {
        "  RECUSADO (HTTP $([int]$_.Exception.Response.StatusCode)) -- sem substituto nao ha 3 replicas vivas."
        "  E a diferenca entre quorum estrito e quorum relaxado, na mesma chave."
    }

    Titulo "Passo 5 - subindo $NoAlvo e medindo a recuperacao"
    $cronometro = [System.Diagnostics.Stopwatch]::StartNew()
    docker compose start $NoAlvo | Out-Null
    "  $NoAlvo iniciado; aguardando a entrega dos hints..."

    $recuperado = $false
    while ($cronometro.Elapsed.TotalSeconds -lt 90) {
        Start-Sleep -Milliseconds 500
        $h = HintsNoCluster
        if ($h.Total -eq 0) {
            $cronometro.Stop()
            $recuperado = $true
            break
        }
    }

    if ($recuperado) {
        ''
        "  TEMPO DE RECUPERACAO: $([math]::Round($cronometro.Elapsed.TotalSeconds,2)) s"
        '  (do start do container ate o ultimo hint entregue)'
        '  Este e o numero que vai no relatorio.'
    } else {
        "  hints nao zeraram em 90 s -- ainda pendentes: $($h.Total)"
    }

    Titulo "Passo 6 - $NoAlvo recuperou o dado que perdeu?"
    $idx = [int]($NoAlvo -replace '\D', '')
    $dump = Invoke-RestMethod "$BaseUrl$idx/debug/dump" -TimeoutSec 10
    if ($dump.dados.$chave) {
        $vals = ($dump.dados.$chave | ForEach-Object { $_.valor }) -join ' ; '
        "  SIM -- $NoAlvo tem: $vals"
        '  Veio pela entrega do hint, sem ninguem ter feito leitura da chave.'
        '  Sem hinted handoff, esta escrita teria se perdido ate alguem ler.'
    } else {
        "  NAO -- $NoAlvo ainda nao tem a chave. Investigar a entrega."
    }
} finally {
    Pop-Location
}
