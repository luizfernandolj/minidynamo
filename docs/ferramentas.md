# Levantamento de ferramentas

> Entregável 2 do enunciado: ferramentas, frameworks ou serviços que implementam os conceitos estudados, indicando plataformas suportadas.

## Bancos com consistência ajustável (quóruns estilo Dynamo)

| Ferramenta | Modelo | Como se configura a consistência | Plataformas |
|---|---|---|---|
| **Apache Cassandra** | quóruns por operação, relógio por célula com last-writer-wins | `CONSISTENCY ONE / QUORUM / ALL / LOCAL_QUORUM` no cqlsh ou no driver | Linux, macOS, Windows (via WSL/Docker), contêineres, nuvem |
| **ScyllaDB** | igual ao Cassandra, reescrito em C++ | mesma API CQL | Linux, contêineres, nuvem |
| **Amazon DynamoDB** | serviço gerenciado; leitura eventual ou forte | `ConsistentRead: true/false` na API | nuvem AWS; DynamoDB Local em Java para desenvolvimento |
| **Riak KV** | o mais fiel ao paper do Dynamo; vector clocks e siblings expostos | `n_val`, `r`, `w`, `pr`, `pw` por bucket ou requisição | Linux, macOS; contêineres |
| **MongoDB** | replica set com primário | `writeConcern` (`w:1`, `w:"majority"`) e `readConcern` (`local`, `majority`, `linearizable`) | Linux, Windows, macOS, contêineres, Atlas |
| **Couchbase / CouchDB** | eventual com revisões e resolução de conflito | durabilidade por operação | Linux, Windows, macOS, contêineres |

> O que nos distingue: expomos os **siblings** ao cliente, como Riak e o Dynamo original. Cassandra resolve por last-writer-wins e descarta silenciosamente a escrita concorrente — vale contrastar isso no seminário.
>
> Também seguimos o Riak na decisão de tornar o quórum relaxado **explícito**: nosso `?sloppy=` liga e desliga o hinted handoff por requisição, enquanto Dynamo e Cassandra o mantêm sempre ativo. A razão está no [relatório](relatorio.md#6-limitações): sloppy quorum quebra a interseção de quóruns, e deixar isso implícito esconde a perda de garantia.

## Sistemas de consistência forte (consenso, não quórum)

| Ferramenta | Algoritmo | Observação |
|---|---|---|
| **etcd** | Raft | linearizável; base do Kubernetes |
| **CockroachDB / TiDB / YugabyteDB** | Raft por range | SQL distribuído serializável |
| **Google Spanner** | Paxos + TrueTime | consistência externa com relógio atômico |
| **Apache ZooKeeper** | ZAB | coordenação, não armazenamento de dados |

Contraste central do trabalho: **quórum ≠ consenso**. `R+W > N` dá consistência de quórum; linearizabilidade exige consenso. Ver as limitações no [README](../README.md#limitações-que-vão-no-relatório).

## Verificação de consistência

| Ferramenta | Para quê | Plataformas |
|---|---|---|
| **Jepsen** | referência da área; gera histórias de operações e checa linearizabilidade/consistência | Clojure; Linux |
| **Maelstrom** | Jepsen simplificado, para sistemas didáticos — serve para validar nossa implementação | JVM; Linux, macOS |
| **Elle** | checador de serializabilidade usado pelo Jepsen | Clojure |
| **Porcupine** | checador de linearizabilidade em Go | multiplataforma |

> O nosso `Bench` é um verificador mínimo: detecta leitura obsoleta comparando relógios vetoriais, e só avalia leituras que o sistema efetivamente serviu (um 503 é indisponibilidade, não violação). Não checa linearizabilidade — e, como o README registra, nosso sistema não a oferece de qualquer forma. Validar com Maelstrom ficou como trabalho futuro.

## Injeção de falhas

| Ferramenta | O que injeta | Plataformas |
|---|---|---|
| **Toxiproxy** | latência, perda, corte de conexão, por proxy TCP | Linux, macOS, Windows, contêineres |
| **Pumba** | caos em contêineres Docker (kill, netem, delay) | Linux + Docker |
| **Chaos Mesh** | caos declarativo em Kubernetes | Kubernetes |
| **tc / netem** | latência e perda no kernel Linux | Linux (requer `NET_ADMIN`) |

> Escolhemos injeção **no processo** (`/chaos/*`) em vez de Toxiproxy ou netem. Justificativa: a equipe desenvolve no Windows, e `netem` exigiria `NET_ADMIN` no contêiner e não se comportaria igual nas três máquinas. O custo é que simulamos perda de mensagem, não falha de kernel.
>
> A injeção de latência acabou sendo mais que conveniência: é o que torna a medição de consistência eventual reprodutível. Sem controlar a janela de replicação, o número medido depende de quão ocupada está a máquina — ver [relatório, seção 3.2](relatorio.md#32-por-que-a-janela-precisa-ser-controlada).

## Observabilidade

| Ferramenta | Uso aqui |
|---|---|
| **Micrometer + Prometheus** | `/metrics` com percentis calculados no nó |
| **Grafana** | painéis (opcional para a demo) |
| **OpenTelemetry** | tracing distribuído (não usado; valeria para seguir uma requisição entre réplicas) |

## Ambiente usado nas nossas medições

| Item | Versão |
|---|---|
| Docker Engine | 29.9.2 (backend WSL2) |
| Docker Compose | v5.6.1 |
| WSL | 3.0.3.0, kernel 6.18.54 |
| JDK | Eclipse Temurin 21.0.13+9 |
| Maven | 3.9.16 |
| Javalin | 6.7.0 |
| Jackson | 2.19.0 |
| Micrometer | 1.15.0 |
| SO | Windows 11 Home 26300 |

Imagem final `minidynamo:1.0.0`: **309 MB** (`eclipse-temurin:21-jre-alpine` + fat jar de 11 MB). Cinco nós ficam `healthy` em ~6 s.

---

## Comparação executada: Cassandra 5.0, 3 nós, RF=3

Subimos um cluster Cassandra real ao lado do nosso (`docker-compose.cassandra.yml`) e rodamos a mesma demonstração. Script: [demo-cassandra.ps1](../scripts/demo-cassandra.ps1).

Chave `c1` com RF=3, replicada em `172.19.0.2`, `.3` e `.4` (confirmado por `nodetool getendpoints`). Depois derrubamos um nó — `nodetool status` passou a mostrar `DN` para ele:

| Nível | Leitura com 1 de 3 fora | Escrita com 1 de 3 fora |
|---|---|---|
| `ONE` | OK | aceita |
| `QUORUM` | OK | aceita |
| `ALL` | **recusada** | **recusada** |

Erro exato na recusa:

> `Cannot achieve consistency level ALL`, `required_replicas: 3, alive_replicas: 2`

Isso espelha exatamente o comportamento do MiniDynamo: `W=2` fecha com um nó fora, `W=3` não. A correspondência de níveis é direta:

| MiniDynamo | Cassandra |
|---|---|
| `modo=eventual` (`R=W=1`) | `CONSISTENCY ONE` |
| `modo=forte` (`R=W=2`, N=3) | `CONSISTENCY QUORUM` |
| `w=3` (N=3) | `CONSISTENCY ALL` |

### A diferença que a comparação revelou

O Cassandra **também** guarda hints para a réplica fora do ar. Mas esses hints **não contam para o nível de consistência**: `CL=ALL` foi recusada mesmo sendo possível armazenar um hint para o nó derrubado. No Cassandra, hinted handoff é um mecanismo de **reparo**, não de disponibilidade.

No Dynamo original — e no nosso `?sloppy=true` — o ack do substituto **conta para W**. Foi por isso que nossa escrita `W=3` foi **aceita** com um nó fora, enquanto a equivalente no Cassandra foi recusada.

Nenhum dos dois está errado: são decisões diferentes sobre o mesmo trade-off. O Cassandra preserva a interseção de quóruns e sacrifica disponibilidade; o Dynamo faz o oposto. Essa comparação é o que nos convenceu a tornar o `sloppy` um parâmetro explícito em vez de ligá-lo em silêncio — ver [relatório, seção 6](relatorio.md#6-limitações).

### Nota operacional

O healthcheck inicial do nosso compose (`nodetool status | grep -q '^UN'`) deixava o nó se declarar saudável enquanto ainda entrava no anel. Com isso o terceiro nó começava a bootstrapar em paralelo com o segundo e morria em `TokenMetadata.addBootstrapTokens` — o Cassandra não tolera joins simultâneos. Trocado por `cqlsh -e 'describe cluster'`, que só responde depois do transporte nativo ativo, serializando os joins de verdade. O anel de 3 nós forma em ~70 s por nó.

Consumo observado: ~1,2 GB por nó de Cassandra, contra ~105 MB por nó do MiniDynamo.
