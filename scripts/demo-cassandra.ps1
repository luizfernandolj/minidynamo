# Comparacao com Cassandra: a mesma demonstracao, num sistema de producao.
#
# Roda o equivalente dos nossos modos contra um cluster Cassandra de 3 nos com
# RF=3, derruba um no e mostra o comportamento de CL=ONE / QUORUM / ALL.
#
# Pre-requisitos:
#   docker compose -f docker-compose.cassandra.yml up -d
#   (cada no pede ~1,2 GB; os tres levam alguns minutos para formar o anel)
#
# Uso: .\scripts\demo-cassandra.ps1

param([string]$NoAlvo = 'cass3')

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$env:PATH = "C:\Program Files\Docker\Docker\resources\bin;$env:PATH"
$tmp = Join-Path $env:TEMP 'minidynamo-cql'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Titulo($texto) {
    ''
    '=' * 72
    "  $texto"
    '=' * 72
}

# O cqlsh recebe o script por arquivo, nao por pipe: o pipe do PowerShell
# injeta um BOM UTF-8 que o cqlsh rejeita com "Invalid syntax at line 1".
function RodarCql($nome, $conteudo) {
    $arquivo = Join-Path $tmp $nome
    [System.IO.File]::WriteAllText($arquivo, $conteudo, (New-Object System.Text.UTF8Encoding($false)))
    docker cp $arquivo "cass1:/tmp/$nome" | Out-Null
    return (docker exec cass1 cqlsh -f "/tmp/$nome" 2>&1)
}

function AnelCassandra {
    docker exec cass1 nodetool status 2>&1 | Select-String -Pattern '^(UN|DN)' |
        ForEach-Object { "    $($_ -replace '\s+', ' ')" }
}

Push-Location $raiz
try {
    Titulo 'Passo 0 - estado do anel'
    AnelCassandra

    Titulo 'Passo 1 - keyspace com RF=3 e escrita em QUORUM'
    $saida = RodarCql 'setup.cql' @"
CREATE KEYSPACE IF NOT EXISTS loja WITH replication = {'class':'SimpleStrategy','replication_factor':3};
USE loja;
CREATE TABLE IF NOT EXISTS carrinho (id text PRIMARY KEY, itens text);
CONSISTENCY QUORUM;
INSERT INTO carrinho (id, itens) VALUES ('c1', 'livro');
SELECT id, itens FROM carrinho WHERE id='c1';
"@
    $saida | Select-String -Pattern 'Consistency|livro|rows' | ForEach-Object { "  $_" }
    ''
    '  replicas que guardam a chave c1:'
    docker exec cass1 nodetool getendpoints loja carrinho c1 2>&1 | ForEach-Object { "    $_" }

    Titulo "Passo 2 - derrubando $NoAlvo"
    docker compose -f docker-compose.cassandra.yml stop $NoAlvo | Out-Null
    Start-Sleep -Seconds 20
    AnelCassandra

    Titulo 'Passo 3 - LEITURA com 1 de 3 replicas fora'
    foreach ($nivel in 'ONE', 'QUORUM', 'ALL') {
        $r = RodarCql "r_$nivel.cql" "USE loja;`nCONSISTENCY $nivel;`nSELECT itens FROM carrinho WHERE id='c1';`n"
        if ($r | Select-String -Pattern 'livro' -Quiet) {
            "  CL=$nivel  -> OK, leu o valor"
        } else {
            $msg = ($r | Select-String -Pattern 'required_replicas' | Select-Object -First 1)
            "  CL=$nivel  -> RECUSADA (Cannot achieve consistency level $nivel)"
            if ($msg) { "              $(($msg -split 'info=')[-1])" }
        }
    }

    Titulo 'Passo 4 - ESCRITA com 1 de 3 replicas fora'
    foreach ($nivel in 'ONE', 'QUORUM', 'ALL') {
        $r = RodarCql "w_$nivel.cql" "USE loja;`nCONSISTENCY $nivel;`nINSERT INTO carrinho (id, itens) VALUES ('w-$nivel', 'teste');`n"
        if ($r | Select-String -Pattern 'Unavailable|NoHostAvailable|cannot achieve' -Quiet) {
            "  CL=$nivel  -> RECUSADA"
        } else {
            "  CL=$nivel  -> aceita"
        }
    }

    Titulo 'Passo 5 - hints acumulados pelo Cassandra'
    docker exec cass1 nodetool tpstats 2>&1 | Select-String -Pattern 'HintsDispatcher|Hint' | ForEach-Object { "  $($_ -replace '\s+',' ')" }
    ''
    '  O Cassandra TAMBEM guarda hints para a replica fora. A diferenca'
    '  conceitual e que esses hints NAO contam para o nivel de consistencia:'
    '  CL=ALL foi recusada no passo 4 mesmo havendo hint possivel. Sao um'
    '  mecanismo de REPARO, nao de disponibilidade.'
    ''
    '  No Dynamo original, e no nosso ?sloppy=true, o ack do substituto CONTA'
    '  para W -- e por isso nossa escrita W=3 foi aceita com um no fora. Essa'
    '  e a diferenca que justifica termos feito o sloppy explicito.'

    Titulo "Passo 6 - subindo $NoAlvo de volta"
    docker compose -f docker-compose.cassandra.yml start $NoAlvo | Out-Null
    '  aguardando reentrada no anel...'
    foreach ($i in 1..30) {
        Start-Sleep -Seconds 10
        $un = ([regex]::Matches((docker exec cass1 nodetool status 2>&1 | Out-String), '(?m)^UN\s')).Count
        if ($un -ge 3) { "  anel completo apos ~$($i*10)s"; break }
    }
    AnelCassandra
} finally {
    Pop-Location
}
