# MiniDynamo

KV replicado. Consistência escolhida por requisição. Trabalho de SD 2026.

## Rodar

```bash
docker compose up -d --build
```

Servidores: `sp` :8081 · `rj` :8082 · `ba` :8083

Demos:

```bash
powershell -ExecutionPolicy Bypass -File .\scripts\demo-modos.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\demo-particao.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\demo-queda-de-no.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\demo-anti-entropia.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\demo-falha-eventual.ps1
```

Números:

```bash
powershell -ExecutionPolicy Bypass -File .\scripts\experimentos.ps1 -Repeticoes 3 -Ops 300 -Chaves 30 -LatenciaMs 120 -JitterMs 40
```

Testes:

```bash
mvn -B test
```

## Modos

| modo | escreve | lê | recado | erro |
|---|---|---|---|---|
| `forte` | W=2 | R=2 | não | 503 sem quórum |
| `eventual` | W=1 | R=1 | sim | — |
| `causal` | W=1 | R=1 + recibo | sim | 409 se ninguém tem o recibo |

Parâmetros: `?modo=` `?w=` `?r=` `?sloppy=true|false` · header `X-Contexto` (recibo causal).

## Configuração (`docker-compose.yml`)

| variável | padrão | efeito |
|---|---|---|
| `NODE_ID` | sp/rj/ba | identidade do servidor |
| `REPLICATION_N` | 2 | réplicas por carrinho |
| `DEFAULT_R` / `DEFAULT_W` | 2 / 2 | quando o cliente não manda |
| `HINT_DELIVERY_MS` | 2000 | entrega dos recados |
| `ANTI_ENTROPY_MS` | 5000 | intervalo da anti-entropia |
| `ANTI_ENTROPY_ENABLED` | true | liga/desliga anti-entropia |
| `VNODES` | 128 | nós virtuais por servidor |

## Resultados

Atraso 120 ms ± 40 ms, 3 repetições.

| modo | leituras antigas |
|---|---|
| eventual | 37–44% |
| forte | 0% |
| causal | 0% |

## Falhas demonstradas

- **Partição:** SP e RJ/BA escrevem o mesmo carrinho. Forte recusa do lado isolado. Ao religar: conflito com os dois itens.
- **Queda:** RJ cai. Escrita com recado aceita, sem recado recusada. Ao voltar, recado entregue em segundos.
- **Anti-entropia:** servidor isolado recebe o dado sem leitura, em segundos.
- **Falha no eventual:** SP confirma W=1 e cai antes de copiar. Item confirmado se perde.

## Limites

- Forte não é linearizável: recusa não desfaz escrita parcial.
- Recado quebra a interseção de quóruns; por isso fica desligado no forte.
- Dados só em memória: reiniciar servidor zera a réplica.
- Medido em uma máquina só: latência é relativa, obsolescência não.

## Código

```
src/main/java/sd/minidynamo/
  core/     VersionVector, Reconciliador, HashRing, Store, HintStore
  server/   Coordenador, HintedHandoff, AntiEntropia, Chaos, NodeMain
  bench/    Bench (carga e verificação)
```
