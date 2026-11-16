# Demo 3: convergencia sem leitura (anti-entropia). Uso: .\scripts\demo-anti-entropia.ps1
. "$PSScriptRoot\lib.ps1"
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $chave = "ae-$(Get-Random -Maximum 99999)"
    $pref = @((Invoke-RestMethod "$(Url node1)/debug/anel?chave=$chave").listaDePreferencia)
    $isolado = $pref[2]
    $coord = $pref[0]
    $outros = @('node1', 'node2', 'node3', 'node4', 'node5') | Where-Object { $_ -ne $isolado }

    Cabecalho 3 'Convergencia sem leitura (anti-entropia)'
    Linha '>' "chave $chave  replicas: $($pref -join ', ')"

    Linha '~' "isolando $isolado"
    Cortar @($isolado) @($outros)

    # sloppy=false: nenhum recado. So a anti-entropia pode levar o dado ao isolado.
    Gravar $coord $chave 'valor-novo' eventual 1 '&sloppy=false'
    Linha '>' "$isolado guarda: $(Estado $isolado $chave)"

    Linha '~' 'religando a rede. Ninguem le a chave daqui em diante.'
    Curar
    $inicio = Get-Date
    while ((Estado $isolado $chave) -eq '(vazio)' -and ((Get-Date) - $inicio).TotalSeconds -lt 60) {
        Start-Sleep -Milliseconds 500
    }
    $seg = [math]::Round(((Get-Date) - $inicio).TotalSeconds, 1)

    if ((Estado $isolado $chave) -eq '(vazio)') {
        Linha 'X' "sem convergir em $seg s"
    } else {
        Linha 'ok' "$isolado recebeu o dado sozinho em $seg s"
    }
    Linha '>' 'valor em cada replica:'
    foreach ($no in $pref) { "      $no  $(Estado $no $chave)" }
}
finally { Pop-Location }
