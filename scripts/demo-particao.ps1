# Demo 1: cabo entre SP e o resto cai. Uso: .\scripts\demo-particao.ps1
. "$PSScriptRoot\lib.ps1"
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    Curar   # rede inteira no inicio, mesmo se uma demo anterior foi interrompida
    $ladoA = @('sp')
    $ladoB = @('rj', 'ba')

    # Carrinho guardado em SP e em um dos outros dois.
    $chave = $null
    foreach ($t in 1..300) {
        $c = "carrinho-$(Get-Random -Maximum 999999)"
        $pref = @((Invoke-RestMethod "$(Url sp)/debug/anel?chave=$c").listaDePreferencia)
        if ($pref -contains 'sp') { $chave = $c; break }
    }
    if (-not $chave) { throw 'nenhum carrinho com replica em SP' }
    $outro = @($pref | Where-Object { $_ -ne 'sp' })[0]

    Cabecalho 1 'Cabo SP x resto'
    Linha '>' "carrinho $chave  guardado em: $($pref -join ', ')"
    Linha '>' 'lado A: sp (sozinho)    lado B: rj, ba'

    Gravar $pref[0] $chave 'inicial' forte 2

    Linha '~' 'cabo cai: SP fica isolado'
    Cortar $ladoA $ladoB

    Gravar sp $chave 'livro' eventual 1 '&sloppy=false'
    Gravar $outro $chave 'caneta' eventual 1 '&sloppy=false'
    Linha '>' 'carrinho em cada servidor:'
    foreach ($no in $pref) { "      $no  $(Estado $no $chave)" }

    # Outro carrinho com replica em SP, so para mostrar a recusa.
    $chaveX = $null
    foreach ($t in 1..300) {
        $c = "carrinho-x$(Get-Random -Maximum 999999)"
        if ((@((Invoke-RestMethod "$(Url sp)/debug/anel?chave=$c").listaDePreferencia)) -contains 'sp') { $chaveX = $c; break }
    }
    Linha '~' 'SP tenta confirmar com 2 servidores (forte), em outro carrinho'
    Gravar sp $chaveX 'livro' forte 2

    Linha '~' 'cabo volta'
    Curar
    Ler sp $chave forte 2
    Start-Sleep -Seconds 1

    Linha '>' 'carrinho em cada servidor depois da leitura:'
    foreach ($no in $pref) { "      $no  $(Estado $no $chave)" }
    ''
    Linha '>' 'nenhum item perdido: a aplicacao junta livro + caneta'
}
finally { Pop-Location }
