# MiniDynamo

Armazenamento chave-valor replicado em que **a consistência é parâmetro de requisição, não de cluster**. O mesmo binário, o mesmo dado e a mesma falha produzem resultados diferentes conforme `R` e `W` — e é isso que permite comparar consistência forte, eventual e causal sem manter três sistemas.

Trabalhos 5 e 6 de Sistemas Distribuídos — 2026.
Tema: *Replicação e modelos de consistência: forte, eventual, causal; quóruns no estilo Dynamo/Cassandra.*

**Grupo:** Luiz Fernando Luth Junior · Dyogo Romagna · Gustavo de Almeida Neves

---

## Como rodar

### Com Docker (forma da entrega)

```bash
docker compose up --build
```

Sobe 5 nós em `localhost:8081`…`8085`. Cada um é réplica e coordenador: não existe mestre.

### Sem Docker (desenvolvimento)

```bash
mvn -B -DskipTests package
```

```powershell
.\scripts\local-5nos.ps1
```

Sobe os 5 nós como processos locais nas mesmas portas. Para parar: `.\scripts\local-5nos.ps1 -Parar`.

### Testes

```bash
mvn test
```

26 testes cobrindo relógios vetoriais, reconciliação, anel de hashing e armazenamento local.

---

## Experimento rápido

```bash
docker compose --profile bench run --rm bench --modo=eventual --w=1 --r=1 --rotulo=R1W1
```

Resultado da primeira rodada local (N=3, 400 operações, 40 chaves, concorrência 16):

| Config | Leituras obsoletas | Escrita p50 | Leitura p50 |
|---|---|---|---|
| `R=2 W=2` forte | **0,00%** | 59 ms | 55 ms |
| `R=1 W=1` eventual | **13,25%** | 47 ms | 37 ms |
| `R=1 W=1` causal | **0,00%** | 15 ms | 18 ms |
| `R=3 W=3` forte | **0,00%** | 37 ms | 28 ms |

A coluna de obsolescência é o resultado sólido e reproduz o esperado: `R+W > N` elimina leitura obsoleta, `R=W=1` não.

**As latências desta tabela não servem para o relatório ainda.** Foram medidas com os 5 nós e o bench na mesma máquina, numa única rodada curta, sem aquecimento — por isso `R=3 W=3` aparece mais rápido que `R=2 W=2`, o que é artefato de JIT e não um achado. Para o relatório use `scripts/experimentos.ps1`, que faz aquecimento, repete cada configuração e alterna a ordem.

---

## Arquitetura

```
cliente ──HTTP──> qualquer nó  (todos coordenam, sem master)
                      │
             hashing consistente (128 vnodes/nó)
                      │
              lista de preferência: N réplicas
                      │
     node1   node2   node3   node4   node5
              ↕ read repair / API interna
```

| Componente | Arquivo | Papel |
|---|---|---|
| `VersionVector` | [core/VersionVector.java](src/main/java/sd/minidynamo/core/VersionVector.java) | ordem causal parcial; distingue "mais novo" de "concorrente" |
| `Reconciliador` | [core/Reconciliador.java](src/main/java/sd/minidynamo/core/Reconciliador.java) | descarta ancestrais, preserva siblings |
| `HashRing` | [core/HashRing.java](src/main/java/sd/minidynamo/core/HashRing.java) | particionamento e lista de preferência |
| `Store` | [core/Store.java](src/main/java/sd/minidynamo/core/Store.java) | réplica local, em memória |
| `Coordenador` | [server/Coordenador.java](src/main/java/sd/minidynamo/server/Coordenador.java) | quórum R/W, read repair, modos |
| `Chaos` | [server/Chaos.java](src/main/java/sd/minidynamo/server/Chaos.java) | partição, latência e perda injetadas via HTTP |
| `Bench` | [bench/Bench.java](src/main/java/sd/minidynamo/bench/Bench.java) | carga + verificador de consistência |

### Por que Java 21 e virtual threads

O coordenador dispara N chamadas às réplicas e espera apenas as W (ou R) primeiras, abandonando o resto. Com threads de plataforma isso exigiria um pool dimensionado à mão; com `Executors.newVirtualThreadPerTaskExecutor()` uma thread parada esperando resposta custa quase nada, e o padrão fica legível:

```java
var cs = new ExecutorCompletionService<Ack>(executor);
// ... submete N chamadas ...
while (ok < w && pendentes > 0) {
    var f = cs.poll(restante, TimeUnit.NANOSECONDS);
    // ...
}
```

As tarefas que sobram **não são canceladas** de propósito: essa replicação tardia é literalmente a consistência eventual.

### Por que não Spring Boot

O trabalho mede tempo de recuperação de nó. Os 2–4 s de startup do Spring distorceriam exatamente essa métrica e inflariam 5 containers. Javalin sobe em ~300 ms e a imagem final fica em ~90 MB.

---

## Os três modelos

| Modo | R/W padrão | Garantia | Preço |
|---|---|---|---|
| `forte` | quórum (2 de 3) | `R+W > N`: leitura cruza com escrita em ≥1 réplica, logo não lê valor velho | sem W réplicas vivas, a escrita é recusada (503) |
| `eventual` | 1 / 1 | responde enquanto houver uma réplica de pé, inclusive nos dois lados de uma partição | leitura após escrita pode ser obsoleta; escritas concorrentes divergem |
| `causal` | 1 / 1 + contexto | read-your-writes e monotonic reads sem pagar quórum | se nenhuma réplica viu o contexto do cliente, responde **409** em vez de voltar no tempo |

O modo causal usa o cabeçalho `X-Contexto` com o vetor que o cliente já viu. O coordenador continua consultando réplicas até achar uma que domine esse contexto.

---

## API

### Cliente

```
PUT    /kv/{chave}?modo=forte|eventual|causal&w=N
GET    /kv/{chave}?modo=...&r=N          [header X-Contexto: {"node1":3}]
DELETE /kv/{chave}?modo=...&w=N
```

Corpo da escrita: `{"valor":"...","contexto":{"node1":3}}` (contexto opcional).

Códigos: `200` ok · `404` chave inexistente · `409` contexto causal não atendido · `503` sem quórum.

### Interna (entre nós)

```
PUT /internal/kv/{chave}     aplica uma versão localmente, sem coordenar
GET /internal/kv/{chave}     versões locais cruas
```

### Caos

```
POST /chaos/particao   {"bloqueados":["node3","node4"]}
POST /chaos/latencia   {"ms":200,"jitterMs":50}
POST /chaos/perda      {"probabilidade":0.3}
POST /chaos/curar
GET  /chaos
```

### Diagnóstico

```
GET /health
GET /metrics                      formato Prometheus
GET /debug/anel?chave=x           lista de preferência da chave
GET /debug/anel                   distribuição de carga entre os nós
GET /debug/dump                   estado local cru desta réplica
```

`/debug/dump` é o que permite mostrar a divergência entre os dois lados de uma partição.

---

## Cenários de falha

### 1. Partição de rede — o roteiro da demo

```powershell
.\scripts\demo-particao.ps1
```

Mostra, na mesma chave: cluster saudável → partição `{node1,node2} | {node3,node4,node5}` → ambos os lados aceitam escrita com `W=1` e divergem → com `W=3` o lado minoritário é recusado → cura → read repair converge e as escritas concorrentes aparecem como **siblings**, não como dado perdido.

### 2. Queda de nó

```bash
docker compose stop node3
```

Com `R=W=2` o cluster continua; com `R=W=3` fica indisponível para as chaves que têm node3 na lista de preferência. Use `/debug/anel?chave=X` para escolher uma chave cuja lista inclua exatamente o nó que vai cair.

### 3. Latência e perda de mensagem

```bash
curl -X POST localhost:8081/chaos/latencia -d '{"ms":200,"jitterMs":50}' -H 'Content-Type: application/json'
```

Mostra o p95 subindo conforme R e W crescem — o trade-off latência × consistência, medido.

---

## O que está implementado

- [x] Hashing consistente com nós virtuais e lista de preferência
- [x] Quóruns R/W configuráveis por requisição; qualquer nó coordena
- [x] Relógios vetoriais, detecção de siblings e reconciliação
- [x] Read repair no caminho de leitura
- [x] Tombstones para remoção
- [x] Injeção de partição, latência e perda via HTTP
- [x] Métricas Prometheus com percentis
- [x] Bench com verificação de obsolescência por relógio vetorial
- [x] 26 testes unitários do núcleo

## O que falta (em ordem de prioridade)

- [ ] **Hinted handoff / sloppy quorum** — hoje, se uma réplica está fora, a escrita simplesmente falha nela. Com handoff, outro nó guarda a escrita e a entrega na volta. É o que produz o número de *tempo de recuperação* que o relatório pede: é a próxima tarefa.
- [ ] **Anti-entropia** (gossip de digests ou árvore de Merkle) — hoje a convergência depende de uma leitura acontecer. Sem leitura, duas réplicas podem ficar divergentes indefinidamente.
- [ ] **Membership dinâmico** — a topologia vem fixa de `PEERS`; entrar/sair de nó exige reiniciar.
- [ ] **Merge de carrinho de compras** — os valores são strings opacas. Trocar por um carrinho (união de itens) faria o merge de siblings ter significado, que é o exemplo canônico do paper do Dynamo.
- [ ] **Persistência** — tudo em memória; reiniciar um nó zera sua réplica.

---

## Limitações que vão no relatório

**`R+W > N` não dá linearizabilidade.** Dá "consistência de quórum". Faltam duas coisas: as escritas não são atômicas entre réplicas (um 503 por falta de quórum **não** desfaz o que já gravou nas réplicas que responderam — o dado fica lá), e quando houver sloppy quorum as réplicas que respondem podem não ser as da lista de preferência. Quem precisa de linearizabilidade usa consenso (Raft/Paxos), não quórum — e é por isso que etcd existe.

**Coordenador fora da lista de preferência faz escrita cega.** Qualquer nó aceita coordenar qualquer chave. Se o coordenador não é réplica daquela chave, ele não conhece o relógio atual e parte do vetor vazio, o que gera sibling sob concorrência. No Dynamo real o cliente roteia para um nó da lista de preferência. Dá para ver isso no passo 4 da demo: `node5` coordena e produz `{"node5":1}`, concorrente com `{"node1":1}`.

**A injeção de caos simula perda de mensagem, não queda de processo.** São comportamentos diferentes e vale discutir os dois: `/chaos/particao` mantém o processo vivo com estado; `docker compose stop` mata o processo.

**Medição em máquina única.** 5 JVMs e o bench competindo pelos mesmos núcleos. Os números de obsolescência são confiáveis (são lógicos, não temporais); os de latência exigem aquecimento e repetição, e mesmo assim medem um cluster co-residente.

---

## Divisão do trabalho

| Quem | Frente |
|---|---|
| Luiz Fernando | núcleo: anel, relógios vetoriais, coordenador de quórum |
| Dyogo | infraestrutura e caos: compose, cenários de falha, hinted handoff, anti-entropia |
| Gustavo | bench, verificador de consistência, métricas e gráficos, documento técnico |

---

## Referências

- DeCandia et al. **Dynamo: Amazon's Highly Available Key-value Store.** SOSP 2007.
- Vogels, W. **Eventually Consistent.** Communications of the ACM, 2009.
- Lamport, L. **Time, Clocks, and the Ordering of Events in a Distributed System.** CACM, 1978.
- Apache Cassandra — [documentação de consistência ajustável](https://cassandra.apache.org/doc/latest/cassandra/architecture/dynamo.html)
- Bailis & Ghodsi. **Eventual Consistency Today: Limitations, Extensions, and Beyond.** ACM Queue, 2013.
