# Demo 2: queda de um no, com recado (hinted handoff).
# Uso: .\scripts\demo-queda-de-no.ps1 -NoAlvo node3
param([string]$NoAlvo = 'node3')
. "$PSScriptRoot\lib.ps1"
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    # Chave que tem o no alvo entre as replicas.
    $chave = $null
    foreach ($t in 1..300) {
        $c = "queda-$t"
        $pref = @((Invoke-RestMethod "$(Url node1)/debug/anel?chave=$c").listaDePreferencia)
        if ($pref -contains $NoAlvo) { $chave = $c; break }
    }
    if (-not $chave) { throw "nenhuma chave com $NoAlvo na lista de preferencia" }

    Cabecalho 2 "Queda de $NoAlvo"
    Linha '>' "chave $chave  replicas: $($pref -join ', ')"

    Gravar node1 $chave 'antes' forte 3

    Linha '~' "derrubando $NoAlvo"
    # cmd /c: o docker escreve progresso no stderr, e o PowerShell 5.1 o mostra como erro.
    cmd /c "docker compose stop $NoAlvo >nul 2>&1"

    Gravar node1 $chave 'durante' eventual 3
    Gravar node1 $chave 'sem-recado' eventual 3 '&sloppy=false'

    Linha '~' "religando $NoAlvo"
    $inicio = Get-Date
    cmd /c "docker compose start $NoAlvo >nul 2>&1"
    while ((RecadosPendentes) -gt 0 -and ((Get-Date) - $inicio).TotalSeconds -lt 90) {
        Start-Sleep -Milliseconds 300
    }
    $seg = [math]::Round(((Get-Date) - $inicio).TotalSeconds, 1)
    Linha 'ok' "recados entregues em $seg s"

    Linha '!' "$NoAlvo voltou vazio (memoria): 'antes' se perdeu"
    Linha '>' "$NoAlvo agora guarda: $(Estado $NoAlvo $chave)"
    Linha '>' 'nenhuma leitura foi feita: o dado veio so pelo recado'
}
finally { Pop-Location }
