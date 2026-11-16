# Demo 4: o mesmo carrinho nos tres modos. Uso: .\scripts\demo-modos.ps1
# Cenario: usuario em SP adiciona "livro"; logo depois o carrinho e lido em BA.
. "$PSScriptRoot\lib.ps1"
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    Cabecalho 4 'Mesmo carrinho, tres modos'
    # 1 s de atraso entre servidores: a copia leva 1 s para chegar em BA. Com
    # 120 ms o PowerShell ja gasta quase isso entre a escrita e a leitura, e o
    # caso eventual nao aparece.
    # Garante rede inteira no inicio: um cabo cortado de uma demo anterior
    # faria o forte recusar e o causal responder 409.
    Curar
    Linha '~' 'atraso de 1 s entre servidores (distancia SP-BA)'
    Latencia 1000

    # ---------------------------------------------------------------- FORTE
    ''
    Linha '>' 'FORTE: SP so responde depois que BA confirma (W=2)'
    $k = ChaveEntre 'forte-' @('sp', 'ba')
    Gravar sp $k 'livro' forte 2
    Ler ba $k forte 2

    # -------------------------------------------------------------- EVENTUAL
    ''
    Linha '>' 'EVENTUAL: SP responde na hora (W=1); BA recebe depois'
    $k = ChaveEntre 'evento-' @('sp', 'ba')
    Gravar sp $k 'livro' eventual 1
    Ler ba $k eventual 1
    if (@($global:UltimaLeitura.valores).Count -eq 0) { Linha '!' 'leitura antiga: BA ainda nao tinha o livro' }
    Start-Sleep -Milliseconds 1500
    Linha '~' 'um instante depois (a copia ja chegou)'
    Ler ba $k eventual 1

    # ---------------------------------------------------------------- CAUSAL
    ''
    Linha '>' 'CAUSAL: SP responde na hora (W=1) e devolve um recibo'
    $k = ChaveEntre 'causal-' @('sp', 'ba')
    Gravar sp $k 'livro' causal 1
    $recibo = $global:UltimaEscrita.contexto
    Linha '>' "recibo do usuario: $(ConvertTo-Json -Compress $recibo)"
    Ler ba $k causal 1
    if (@($global:UltimaLeitura.valores).Count -eq 0) { Linha '!' 'sem recibo: BA ainda nao tinha o livro' }
    Ler ba $k causal 1 $recibo

    Curar
    ''
    Linha '>' 'FORTE: nunca le antigo, paga a espera de BA'
    Linha '>' 'EVENTUAL: responde rapido, pode ler antigo'
    Linha '>' 'CAUSAL: responde rapido, e o recibo impede o carrinho de voltar no tempo'
}
finally { Pop-Location }
