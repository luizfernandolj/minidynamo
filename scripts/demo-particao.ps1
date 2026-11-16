# Demo 1: particao de rede. Uso: .\scripts\demo-particao.ps1
. "$PSScriptRoot\lib.ps1"
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $lado1 = @('node1', 'node2')
    $lado2 = @('node3', 'node4', 'node5')

    # Escolhe uma chave com replicas dos DOIS lados, senao a demo nao divide nada.
    $chave = $null
    foreach ($t in 1..300) {
        $c = "carrinho-$t"
        $pref = @((Invoke-RestMethod "$(Url node1)/debug/anel?chave=$c").listaDePreferencia)
        if (($pref | Where-Object { $lado1 -contains $_ }) -and ($pref | Where-Object { $lado2 -contains $_ })) {
            $chave = $c; break
        }
    }
    if (-not $chave) { throw 'nenhuma chave com replicas dos dois lados' }
    $ladoA = $pref | Where-Object { $lado1 -contains $_ } | Select-Object -First 1
    $ladoB = $pref | Where-Object { $lado2 -contains $_ } | Select-Object -First 1

    Cabecalho 1 'Particao de rede'
    Linha '>' "chave $chave  replicas: $($pref -join ', ')"
    Linha '>' "lado A: $($lado1 -join ', ')   lado B: $($lado2 -join ', ')"

    Gravar $pref[0] $chave 'inicial' forte 2

    Linha '~' 'cortando a rede'
    Cortar $lado1 $lado2

    Gravar $ladoA $chave 'minoria' eventual 1 '&sloppy=false'
    Gravar $ladoB $chave 'maioria' eventual 1 '&sloppy=false'
    Linha '>' 'valor em cada replica:'
    foreach ($no in $pref) { "      $no  $(Estado $no $chave)" }

    # Outra chave, so para mostrar a recusa: exigir W=3 com o lado A isolado.
    Linha '~' 'lado A tenta W=3 numa chave separada'
    Gravar $ladoA "$chave-x" 'tentativa' forte 3

    Linha '~' 'religando a rede'
    Curar
    # R=3 consulta as tres replicas, entao o reparo alcanca todas.
    Ler $ladoA $chave forte 3
    Start-Sleep -Seconds 1

    Linha '>' 'valor em cada replica depois da leitura:'
    foreach ($no in $pref) { "      $no  $(Estado $no $chave)" }
    ''
    Linha '>' 'nada foi apagado: o conflito foi devolvido para a aplicacao resolver'
}
finally { Pop-Location }
