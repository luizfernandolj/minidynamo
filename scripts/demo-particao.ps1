# Cenario de falha: PARTICAO DE REDE.
#
# Este e o roteiro da demo ao vivo. Mostra, na mesma chave e com o mesmo
# binario, que:
#   1. com W=1 os dois lados da particao aceitam escritas e divergem;
#   2. com W=3 o lado minoritario se recusa a escrever (sacrifica A por C);
#   3. ao curar a particao, read repair faz os lados convergirem -- e as
#      escritas concorrentes aparecem como siblings, nao como dado perdido.
#
# Uso:  .\scripts\demo-particao.ps1  [-BaseUrl http://localhost:808]

param([string]$BaseUrl = 'http://localhost:808')

$ErrorActionPreference = 'Stop'

function No($i) { "$BaseUrl$i" }

function Titulo($texto) {
    ''
    '=' * 72
    "  $texto"
    '=' * 72
}

function Escrever($noIdx, $chave, $valor, $modo, $w) {
    $url = "$(No $noIdx)/kv/$chave" + "?modo=$modo" + $(if ($w) { "&w=$w" } else { '' })
    $corpo = @{ valor = $valor } | ConvertTo-Json -Compress
    try {
        $r = Invoke-RestMethod -Method Put -Uri $url -Body $corpo -ContentType 'application/json' -TimeoutSec 10
        # Write-Host e nao saida de pipeline: o chamador costuma mandar o valor
        # de retorno para Out-Null, e a mensagem iria junto.
        Write-Host "  node$noIdx  ESCREVEU '$valor'  ok=$($r.replicasOk)/$($r.w)  quorum=$($r.quorumAtingido)  ctx=$($r.contexto | ConvertTo-Json -Compress)"
        return $r
    } catch {
        $resp = $_.Exception.Response
        $codigo = if ($resp) { [int]$resp.StatusCode } else { 0 }
        Write-Host "  node$noIdx  RECUSOU a escrita (HTTP $codigo) -- sem quorum de escrita"
        return $null
    }
}

function Ler($noIdx, $chave, $modo, $r) {
    $url = "$(No $noIdx)/kv/$chave" + "?modo=$modo" + $(if ($r) { "&r=$r" } else { '' })
    try {
        $resp = Invoke-RestMethod -Method Get -Uri $url -TimeoutSec 10
        $valores = ($resp.valores | ForEach-Object { $_.valor }) -join ' | '
        Write-Host "  node$noIdx  LEU [$valores]  replicasOk=$($resp.replicasOk)/$($resp.r)  conflito=$($resp.conflito)"
        return $resp
    } catch {
        $resp = $_.Exception.Response
        $codigo = if ($resp) { [int]$resp.StatusCode } else { 0 }
        Write-Host "  node$noIdx  leitura falhou (HTTP $codigo)"
        return $null
    }
}

function Particionar($noIdx, $bloqueados) {
    $corpo = @{ bloqueados = $bloqueados } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$(No $noIdx)/chaos/particao" -Body $corpo -ContentType 'application/json' | Out-Null
}

function Curar($noIdx) {
    Invoke-RestMethod -Method Post -Uri "$(No $noIdx)/chaos/curar" -ContentType 'application/json' | Out-Null
}

# ---------------------------------------------------------------------------

$chave = "carrinho-demo-$(Get-Random -Maximum 9999)"

Titulo "Passo 0 - onde esta a chave '$chave'"
$anel = Invoke-RestMethod "$(No 1)/debug/anel?chave=$chave"
"  lista de preferencia: $($anel.listaDePreferencia -join ', ')"
'  (so os nos desta lista guardam a chave; os outros apenas coordenam)'

Titulo 'Passo 1 - cluster saudavel, modo forte'
Escrever 1 $chave 'estado-inicial' 'forte' $null | Out-Null
Ler 5 $chave 'forte' $null | Out-Null
'  Leitura em outro no devolve o valor recem-escrito: R+W > N garante o cruzamento.'

Titulo 'Passo 2 - abrindo a particao  {node1,node2} | {node3,node4,node5}'
Particionar 1 @('node3', 'node4', 'node5')
Particionar 2 @('node3', 'node4', 'node5')
Particionar 3 @('node1', 'node2')
Particionar 4 @('node1', 'node2')
Particionar 5 @('node1', 'node2')
'  particao ativa (injetada no proprio processo, via /chaos/particao)'

Titulo 'Passo 3 - com W=1 os DOIS lados aceitam escrever (AP)'
Escrever 1 $chave 'escrito-pela-minoria' 'eventual' 1 | Out-Null
Escrever 5 $chave 'escrito-pela-maioria' 'eventual' 1 | Out-Null
'  Nenhum lado sabe do outro. O dado divergiu -- e o sistema continuou disponivel.'

Titulo 'Passo 4 - estado local cru de cada replica (a divergencia)'
foreach ($i in 1..5) {
    $dump = Invoke-RestMethod "$(No $i)/debug/dump"
    $versoes = $dump.dados.$chave
    if ($versoes) {
        $txt = ($versoes | ForEach-Object { "$($_.valor) @ $($_.relogio | ConvertTo-Json -Compress)" }) -join '  ;  '
        "  node$i : $txt"
    } else {
        "  node$i : (nao guarda esta chave)"
    }
}

Titulo 'Passo 5 - com W=3 o lado minoritario e recusado (CP)'
Escrever 1 $chave 'tentativa-com-quorum' 'forte' 3 | Out-Null
'  O lado de 2 nos nao consegue 3 acks: prefere recusar a divergir.'

Titulo 'Passo 6 - curando a particao'
1..5 | ForEach-Object { Curar $_ }
'  rede restabelecida'

Titulo 'Passo 7 - leitura com quorum forca a convergencia (read repair)'
$leitura = Ler 1 $chave 'forte' 3
if ($leitura -and $leitura.conflito) {
    ''
    '  >>> SIBLINGS: as duas escritas concorrentes sobreviveram.'
    '      O sistema nao escolheu por timestamp -- devolveu o conflito para a'
    '      aplicacao resolver. Em um carrinho de compras, o merge seria a uniao'
    '      dos itens; last-writer-wins teria jogado uma das escritas no lixo.'
}

Start-Sleep -Seconds 1
Titulo 'Passo 8 - estado local apos o reparo (convergiu)'
foreach ($i in 1..5) {
    $dump = Invoke-RestMethod "$(No $i)/debug/dump"
    $versoes = $dump.dados.$chave
    if ($versoes) {
        $txt = ($versoes | ForEach-Object { $_.valor }) -join '  ;  '
        "  node$i : $txt"
    } else {
        "  node$i : (nao guarda esta chave)"
    }
}
''
'fim da demo.'
