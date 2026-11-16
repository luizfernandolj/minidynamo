# Funcoes compartilhadas pelas demos. Saida curta: uma linha por evento.
# Somente ASCII: o PowerShell 5.1 le .ps1 sem BOM como ANSI e quebra acentos.
$ErrorActionPreference = 'Stop'
$env:PATH = "C:\Program Files\Docker\Docker\resources\bin;$env:PATH"

function Url([string]$no) {
    "http://localhost:808$([int]($no -replace '\D', ''))"
}

function Cabecalho([string]$numero, [string]$titulo) {
    ''
    "=== DEMO $numero - $titulo ==="
}

function Linha([string]$simbolo, [string]$texto) {
    # ok = deu certo | X = recusado/falhou | ~ = acao do roteiro | ! = conflito | > = informacao
    "  [$simbolo] $texto"
}

function Valores($resposta) {
    $v = @($resposta.valores | ForEach-Object { '"' + $_.valor + '"' })
    if ($v.Count -eq 0) { return '(nada)' }
    return ($v -join ' + ')
}

# Estado LOCAL da replica, lido direto no no (nao passa pelo coordenador,
# logo nao dispara read repair).
function Estado([string]$no, [string]$chave) {
    $d = Invoke-RestMethod "$(Url $no)/debug/dump" -TimeoutSec 10
    $v = $d.dados.$chave
    if ($v) { return (@($v | ForEach-Object { '"' + $_.valor + '"' }) -join ' + ') }
    return '(vazio)'
}

function Gravar([string]$no, [string]$chave, [string]$valor, [string]$modo, [int]$w, [string]$extra = '') {
    $corpo = @{ valor = $valor } | ConvertTo-Json -Compress
    $url = "$(Url $no)/kv/${chave}?modo=$modo&w=$w$extra"
    try {
        $r = Invoke-RestMethod -Method Put -Uri $url -Body $corpo -ContentType 'application/json' -TimeoutSec 15
        $recado = if ($r.hints) { "  recado: $($r.hints -join ', ')" } else { '' }
        Linha 'ok' "$no grava `"$valor`"  W=$w  confirmaram $($r.replicasOk)/$w$recado"
    } catch {
        $confirmados = '?'
        try { $confirmados = ($_.ErrorDetails.Message | ConvertFrom-Json).replicasOk } catch { }
        Linha 'X' "$no grava `"$valor`"  W=$w  RECUSADO: so $confirmados de $w confirmaram"
    }
}

function Ler([string]$no, [string]$chave, [string]$modo, [int]$r) {
    try {
        $resp = Invoke-RestMethod "$(Url $no)/kv/${chave}?modo=$modo&r=$r" -TimeoutSec 15
        if ($resp.conflito) { Linha '!' "$no le -> CONFLITO: $(Valores $resp)  (siblings)" }
        else { Linha 'ok' "$no le -> $(Valores $resp)" }
    } catch {
        $codigo = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { '?' }
        Linha 'X' "$no le -> RECUSADO (HTTP $codigo)"
    }
}

# Corta a comunicacao entre dois grupos de nos, nos dois sentidos.
function Cortar([string[]]$lado1, [string[]]$lado2) {
    foreach ($no in $lado1) {
        Invoke-RestMethod -Method Post -Uri "$(Url $no)/chaos/particao" `
            -Body (@{ bloqueados = @($lado2) } | ConvertTo-Json -Compress) `
            -ContentType 'application/json' | Out-Null
    }
    foreach ($no in $lado2) {
        Invoke-RestMethod -Method Post -Uri "$(Url $no)/chaos/particao" `
            -Body (@{ bloqueados = @($lado1) } | ConvertTo-Json -Compress) `
            -ContentType 'application/json' | Out-Null
    }
}

function Curar {
    foreach ($i in 1..5) {
        try { Invoke-RestMethod -Method Post -Uri "http://localhost:808$i/chaos/curar" | Out-Null } catch { }
    }
}

# Quantos recados ainda estao pendentes no cluster inteiro.
function RecadosPendentes {
    $total = 0
    foreach ($i in 1..5) {
        try { $total += (Invoke-RestMethod "http://localhost:808$i/debug/hints" -TimeoutSec 3).total } catch { }
    }
    return $total
}
