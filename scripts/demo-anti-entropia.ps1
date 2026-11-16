# Demo 3: convergencia sem leitura. Uso: .\scripts\demo-anti-entropia.ps1
. "$PSScriptRoot\lib.ps1"
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $chave = "ae-$(Get-Random -Maximum 99999)"
    $pref = @((Invoke-RestMethod "$(Url sp)/debug/anel?chave=$chave").listaDePreferencia)
    $coord = $pref[0]
    $isolado = $pref[1]
    $outros = @($NOS | Where-Object { $_ -ne $isolado })

    Cabecalho 3 'Convergencia sem leitura (anti-entropia)'
    Linha '>' "carrinho $chave  guardado em: $($pref -join ', ')"

    Linha '~' "$isolado fica isolado da rede"
    Cortar @($isolado) $outros

    # sloppy=false: nenhum recado. So a anti-entropia pode levar o dado ate o isolado.
    Gravar $coord $chave 'livro' eventual 1 '&sloppy=false'
    Linha '>' "$isolado guarda: $(Estado $isolado $chave)"

    Linha '~' 'rede volta. Ninguem le o carrinho daqui em diante.'
    Curar
    $inicio = Get-Date
    while ((Estado $isolado $chave) -eq '(vazio)' -and ((Get-Date) - $inicio).TotalSeconds -lt 60) {
        Start-Sleep -Milliseconds 500
    }
    $seg = [math]::Round(((Get-Date) - $inicio).TotalSeconds, 1)

    if ((Estado $isolado $chave) -eq '(vazio)') {
        Linha 'X' "sem convergir em $seg s"
    } else {
        Linha 'ok' "$isolado recebeu o carrinho sozinho em $seg s"
    }
    Linha '>' 'carrinho em cada servidor:'
    foreach ($no in $pref) { "      $no  $(Estado $no $chave)" }
}
finally { Pop-Location }
