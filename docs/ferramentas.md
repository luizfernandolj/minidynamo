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

> O nosso `Bench` é um verificador mínimo: detecta leitura obsoleta comparando relógios vetoriais. Não checa linearizabilidade — e, como o README registra, nosso sistema não a oferece de qualquer forma.

## Injeção de falhas

| Ferramenta | O que injeta | Plataformas |
|---|---|---|
| **Toxiproxy** | latência, perda, corte de conexão, por proxy TCP | Linux, macOS, Windows, contêineres |
| **Pumba** | caos em contêineres Docker (kill, netem, delay) | Linux + Docker |
| **Chaos Mesh** | caos declarativo em Kubernetes | Kubernetes |
| **tc / netem** | latência e perda no kernel Linux | Linux (requer `NET_ADMIN`) |

> Escolhemos injeção **no processo** (`/chaos/*`) em vez de Toxiproxy ou netem. Justificativa: a equipe desenvolve no Windows, e `netem` exigiria `NET_ADMIN` no contêiner e não se comportaria igual nas três máquinas. O custo é que simulamos perda de mensagem, não falha de kernel.

## Observabilidade

| Ferramenta | Uso aqui |
|---|---|
| **Micrometer + Prometheus** | `/metrics` com percentis calculados no nó |
| **Grafana** | painéis (opcional para a demo) |
| **OpenTelemetry** | tracing distribuído (não usado; valeria para seguir uma requisição entre réplicas) |

## A preencher

- [ ] Versões exatas testadas de cada ferramenta citada
- [ ] Print ou log da comparação com o cluster Cassandra (ver `docker-compose.cassandra.yml`)
- [ ] Decidir se vale rodar o Maelstrom contra a nossa implementação
