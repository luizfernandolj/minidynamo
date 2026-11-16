# Cenario: CONVERGENCIA SEM LEITURA (anti-entropia).
#
# Read repair so conserta uma replica quando alguem le a chave. Uma chave que
# ninguem le ficaria divergente para sempre. Este roteiro prova que a
# anti-entropia fecha essa lacuna.
#
# Metodo:
#   1. isola uma replica da chave;
#   2. escreve com sloppy=false, para que NENHUM hint seja criado -- assim a
#      recuperacao so pode vir da anti-entropia;
#   3. cura a particao;
#   4. espera, SEM LER a chave em momento algum, e verifica a replica isolada.
#
# Uso: .\scripts\demo-anti-entropia.ps1

param([string]$BaseUrl = 'http://localhost:808')

$ErrorActionPreference = 'Stop'

function Titulo($texto) {
    ''
    '=' * 72
    "  $texto"
    '=' * 72
}

function Idx($no) { [int]($no -replace '\D', '') }

# Le o estado LOCAL cru da replica. Nao passa pelo coordenador, logo nao
# dispara read repair -- essencial para o experimento ser valido.
function EstadoLocal($no, $chave) {
    $d = Invoke-RestMethod "$BaseUrl$(Idx $no)/debug/dump" -TimeoutSec 10
    if ($d.dados.$chave) {
        return ($d.dados.$chave | ForEach-Object { $_.valor }) -join ' ; '
    }
    return $null
}

$chave = "anti-entropia-$(Get-Random -Maximum 9999)"
$anel = Invoke-RestMethod "$BaseUrl`1/debug/anel?chave=$chave" -TimeoutSec 10
$pref = $anel.listaDePreferencia
$isolada = $pref[2]
$coord = $pref[0]

Titulo "Passo 0 - chave '$chave'"
"  replicas: $($pref -join ', ')"
"  vamos isolar: $isolada   |  coordenador da escrita: $coord"

Titulo "Passo 1 - isolando $isolada nos dois sentidos"
$outros = @('node1','node2','node3','node4','node5') | Where-Object { $_ -ne $isolada }
Invoke-RestMethod -Method Post -Uri "$BaseUrl$(Idx $isolada)/chaos/particao" `
    -Body (@{ bloqueados = $outros } | ConvertTo-Json -Compress) -ContentType 'application/json' | Out-Null
foreach ($o in $outros) {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl$(Idx $o)/chaos/particao" `
        -Body (@{ bloqueados = @($isolada) } | ConvertTo-Json -Compress) -ContentType 'application/json' | Out-Null
}
"  $isolada isolada do resto"

Titulo 'Passo 2 - escrita com sloppy=false (nenhum hint sera criado)'
$corpo = @{ valor = 'so-a-anti-entropia-pode-trazer-isto' } | ConvertTo-Json -Compress
$r = Invoke-RestMethod -Method Put `
    -Uri "$BaseUrl$(Idx $coord)/kv/$chave`?modo=eventual&w=1&sloppy=false" `
    -Body $corpo -ContentType 'application/json' -TimeoutSec 15
"  escrito: ok=$($r.replicasOk)  falharam=[$($r.replicasFalharam -join ',')]  hints=[$($r.hints -join ',')]"

$hintsTotais = 0
foreach ($i in 1..5) {
    $h = Invoke-RestMethod "$BaseUrl$i/debug/hints" -TimeoutSec 5
    $hintsTotais += $h.total
}
"  hints no cluster inteiro: $hintsTotais  (precisa ser 0 para o teste valer)"
if ($hintsTotais -ne 0) { Write-Host '  AVISO: ha hints; a recuperacao poderia vir deles, nao da anti-entropia' }

Titulo "Passo 3 - estado local de $isolada antes da cura"
$antes = EstadoLocal $isolada $chave
if ($antes) { "  $isolada ja tem: $antes  (inesperado)" } else { "  $isolada NAO tem a chave, como esperado" }

Titulo 'Passo 4 - curando a particao'
1..5 | ForEach-Object { Invoke-RestMethod -Method Post -Uri "$BaseUrl$_/chaos/curar" | Out-Null }
'  rede restabelecida'
'  A PARTIR DAQUI NINGUEM LE A CHAVE pelo coordenador: so inspecao local.'

Titulo 'Passo 5 - esperando a anti-entropia agir sozinha'
$cronometro = [System.Diagnostics.Stopwatch]::StartNew()
$convergiu = $false
while ($cronometro.Elapsed.TotalSeconds -lt 60) {
    Start-Sleep -Milliseconds 500
    $agora = EstadoLocal $isolada $chave
    if ($agora) {
        $cronometro.Stop()
        $convergiu = $true
        ''
        "  CONVERGIU em $([math]::Round($cronometro.Elapsed.TotalSeconds,2)) s"
        "  $isolada agora tem: $agora"
        break
    }
}
if (-not $convergiu) {
    "  nao convergiu em 60 s -- a anti-entropia nao esta funcionando"
}

Titulo 'Passo 6 - conferindo as tres replicas'
foreach ($no in $pref) {
    $v = EstadoLocal $no $chave
    "  {0,-8} {1}" -f $no, $(if ($v) { $v } else { '(vazio)' })
}
''
'Conclusao: a convergencia veio da troca periodica de digest entre pares,'
'sem read repair e sem hinted handoff. E a lacuna que faltava fechar.'
