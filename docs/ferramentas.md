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

## A preencher

- [ ] Print ou log da comparação com o cluster Cassandra (ver `docker-compose.cassandra.yml`) — o compose está pronto mas a comparação não foi executada
