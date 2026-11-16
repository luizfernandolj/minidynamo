# Demo 5: falha no modo eventual. O servidor que confirmou a escrita cai antes
# de copiar. Uso: .\scripts\demo-falha-eventual.ps1
. "$PSScriptRoot\lib.ps1"
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    Curar
    # 1 s entre servidores: a copia para BA ainda esta em transito quando SP cai.
    Latencia 1000

    $k = ChaveEntre 'falha-' @('sp', 'ba')

    Cabecalho 5 'Falha no eventual: o servidor que confirmou se perde'
    Linha '>' "carrinho $k  guardado em: sp, ba"

    Gravar ba $k 'caneta' forte 2

    Linha '~' 'usuario adiciona "livro" em SP (eventual, W=1)'
    Gravar sp $k 'livro' eventual 1
    Linha '>' "BA tem neste instante: $(Estado ba $k)"

    Linha '~' 'SP cai agora (docker kill), antes de copiar o livro'
    cmd /c "docker kill md-sp >nul 2>&1"

    Ler ba $k eventual 1
    if (@($global:UltimaLeitura.valores).Count -gt 0 -and ($global:UltimaLeitura.valores | ForEach-Object { $_.valor }) -notcontains 'livro') {
        Linha '!' 'o usuario recebeu "pronto", mas o carrinho mostra o PASSADO: sem livro'
    }

    Linha '~' 'SP volta'
    cmd /c "docker compose start sp >nul 2>&1"
    foreach ($i in 1..30) {
        try { Invoke-RestMethod "$(Url sp)/health" -TimeoutSec 2 | Out-Null; break } catch { Start-Sleep -Seconds 1 }
    }
    Linha '>' "SP agora guarda: $(Estado sp $k)   (a memoria zerou)"

    Linha '~' 'aguardando a anti-entropia trazer o que SP ainda tem'
    $inicio = Get-Date
    while ((Estado sp $k) -eq '(vazio)' -and ((Get-Date) - $inicio).TotalSeconds -lt 60) {
        Start-Sleep -Milliseconds 500
    }
    Linha '>' "SP depois de convergir: $(Estado sp $k)"
    Linha '>' "BA: $(Estado ba $k)"
    ''
    Linha '!' '"livro" foi confirmado com W=1 e nunca copiado: perdido para sempre'
    Linha '>' 'no eventual, "pronto" so significa que UM servidor recebeu'

    Curar
}
finally { Pop-Location }
