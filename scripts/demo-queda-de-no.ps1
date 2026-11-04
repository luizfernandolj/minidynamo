# Cenario de falha: QUEDA DE NO (processo morto, nao particao).
#
# Diferente de /chaos/particao: ali o processo continua vivo com seu estado;
# aqui o no morre e volta vazio, porque o armazenamento e em memoria. Os dois
# casos merecem discussao separada no relatorio.
#
# Requer Docker. Uso:
#   .\scripts\demo-queda-de-no.ps1 -NoAlvo node3

param(
    [string]$NoAlvo = 'node3',
    [string]$BaseUrl = 'http://localhost:808'
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot

function Titulo($texto) {
    ''
    '=' * 72
    "  $texto"
    '=' * 72
}

function Escrever($noIdx, $chave, $valor, $w) {
    $corpo = @{ valor = $valor } | ConvertTo-Json -Compress
    try {
        $r = Invoke-RestMethod -Method Put -Uri "$BaseUrl$noIdx/kv/$chave`?modo=forte&w=$w" `
            -Body $corpo -ContentType 'application/json' -TimeoutSec 10
        Write-Host "  W=$w  ESCREVEU  ok=$($r.replicasOk)/$w  falharam=[$($r.replicasFalharam -join ',')]"
        return $true
    } catch {
        $codigo = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        Write-Host "  W=$w  RECUSADO (HTTP $codigo) -- nao ha $w replicas vivas"
        return $false
    }
}

Push-Location $raiz
try {
    # Escolhe uma chave que tenha o no alvo na lista de preferencia: sem isso o
    # cenario nao prova nada, porque a chave poderia nem morar no no derrubado.
    Titulo "Passo 0 - procurando uma chave cuja lista de preferencia inclua $NoAlvo"
    $chave = $null
    foreach ($tentativa in 1..200) {
        $candidata = "chave-queda-$tentativa"
        $anel = Invoke-RestMethod "$BaseUrl`1/debug/anel?chave=$candidata"
        if ($anel.listaDePreferencia -contains $NoAlvo) {
            $chave = $candidata
            "  chave '$chave' -> replicas: $($anel.listaDePreferencia -join ', ')"
            break
        }
    }
    if (-not $chave) { throw "nenhuma chave encontrada com $NoAlvo na lista de preferencia" }

    Titulo 'Passo 1 - cluster completo'
    Escrever 1 $chave 'antes-da-queda' 3 | Out-Null
    Escrever 1 $chave 'antes-da-queda-w2' 2 | Out-Null

    Titulo "Passo 2 - derrubando $NoAlvo"
    docker compose stop $NoAlvo | Out-Null
    "  $NoAlvo parado"
    Start-Sleep -Seconds 1

    Titulo 'Passo 3 - comportamento com uma replica a menos'
    Escrever 1 $chave 'durante-a-queda-w2' 2 | Out-Null
    '  ^ W=2 ainda fecha: sobraram 2 das 3 replicas'
    Escrever 1 $chave 'durante-a-queda-w3' 3 | Out-Null
    '  ^ W=3 nao fecha: o sistema prefere recusar a aceitar escrita sem as 3'

    Titulo "Passo 4 - subindo $NoAlvo de volta e medindo a recuperacao"
    $cronometro = [System.Diagnostics.Stopwatch]::StartNew()
    docker compose start $NoAlvo | Out-Null

    $recuperado = $false
    while ($cronometro.Elapsed.TotalSeconds -lt 60) {
        $ok = Escrever 1 $chave "pos-recuperacao" 3
        if ($ok) { $recuperado = $true; break }
        Start-Sleep -Milliseconds 500
    }
    $cronometro.Stop()

    if ($recuperado) {
        ''
        "  TEMPO DE RECUPERACAO (ate W=3 voltar a fechar): $([math]::Round($cronometro.Elapsed.TotalSeconds,2)) s"
        '  Este e o numero que vai no relatorio.'
    } else {
        '  nao recuperou em 60 s -- investigar'
    }

    Titulo 'Passo 5 - o no que voltou esta vazio (sem hinted handoff nem anti-entropia)'
    $idx = [int]($NoAlvo -replace '\D', '')
    $dump = Invoke-RestMethod "$BaseUrl$idx/debug/dump"
    if ($dump.dados.$chave) {
        "  $NoAlvo ja tem a chave: $(($dump.dados.$chave | ForEach-Object { $_.valor }) -join ' ; ')"
        '  (chegou pela escrita pos-recuperacao ou por read repair)'
    } else {
        "  $NoAlvo NAO tem a chave."
        '  Esperado: o armazenamento e em memoria e ainda nao existe hinted handoff'
        '  nem anti-entropia. O dado so volta quando uma leitura disparar read repair.'
        '  E exatamente esta a lacuna que a proxima tarefa fecha.'
    }
} finally {
    Pop-Location
}
