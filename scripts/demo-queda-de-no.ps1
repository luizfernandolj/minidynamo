# Demo 2: um servidor cai, com recado. Uso: .\scripts\demo-queda-de-no.ps1 -NoAlvo rj
param([string]$NoAlvo = 'rj')
. "$PSScriptRoot\lib.ps1"
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    # Carrinho que tem o servidor que vai cair entre os dois donos.
    $chave = $null
    foreach ($t in 1..300) {
        $c = "queda-$t"
        $pref = @((Invoke-RestMethod "$(Url sp)/debug/anel?chave=$c").listaDePreferencia)
        if ($pref -contains $NoAlvo) { $chave = $c; break }
    }
    if (-not $chave) { throw "nenhum carrinho com $NoAlvo entre os donos" }
    # Quem coordena precisa ser um dos donos que continuam vivos.
    $coord = @($pref | Where-Object { $_ -ne $NoAlvo })[0]

    Cabecalho 2 "Servidor $NoAlvo cai"
    Linha '>' "carrinho $chave  guardado em: $($pref -join ', ')"

    Gravar $coord $chave 'antes' forte 2

    Linha '~' "$NoAlvo cai"
    cmd /c "docker compose stop $NoAlvo >nul 2>&1"

    Gravar $coord $chave 'durante' eventual 2

    # Outro carrinho com o servidor que caiu, so para mostrar a recusa sem recado.
    $chaveX = $null
    foreach ($t in 1..300) {
        $c = "queda-x$t"
        $pX = @((Invoke-RestMethod "$(Url sp)/debug/anel?chave=$c").listaDePreferencia)
        if ($pX -contains $NoAlvo) {
            $chaveX = $c
            $coordX = @($pX | Where-Object { $_ -ne $NoAlvo })[0]
            break
        }
    }
    Gravar $coordX $chaveX 'sem-recado' eventual 2 '&sloppy=false'

    Linha '~' "$NoAlvo volta"
    $inicio = Get-Date
    cmd /c "docker compose start $NoAlvo >nul 2>&1"
    while ((RecadosPendentes) -gt 0 -and ((Get-Date) - $inicio).TotalSeconds -lt 90) {
        Start-Sleep -Milliseconds 300
    }
    $seg = [math]::Round(((Get-Date) - $inicio).TotalSeconds, 1)
    Linha 'ok' "recados entregues em $seg s"

    Linha '!' "$NoAlvo voltou vazio (memoria): 'antes' se perdeu"
    Linha '>' "$NoAlvo agora guarda: $(Estado $NoAlvo $chave)"
    Linha '>' 'ninguem leu o carrinho para ele voltar'
}
finally { Pop-Location }
