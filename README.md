# MiniDynamo

Armazenamento chave-valor replicado em que **a consistência é parâmetro de requisição, não de cluster**. O mesmo binário, o mesmo dado e a mesma falha produzem resultados diferentes conforme `R` e `W` — e é isso que permite comparar consistência forte, eventual e causal sem manter três sistemas.

Trabalhos 5 e 6 de Sistemas Distribuídos — 2026.
Tema: *Replicação e modelos de consistência: forte, eventual, causal; quóruns no estilo Dynamo/Cassandra.*

**Grupo:** Luiz Fernando Luth Junior · Dyogo Romagna · Gustavo de Almeida Neves

---

## Como rodar

```bash
docker compose up --build
```

Sobe 5 nós em `localhost:8081`…`8085`, saudáveis em ~6 s. Cada um é réplica e coordenador: não existe mestre.

### Sem Docker (desenvolvimento)

```bash
mvn -B -DskipTests package
```

```powershell
.\scripts\local-5nos.ps1
```

### Testes

```bash
mvn test
```

35 testes do núcleo: relógios vetoriais, reconciliação, anel de hashing, armazenamento local e fila de hints.

---

## Resultados

### Obsolescência e latência por configuração

N=3, 300 operações, 30 chaves, concorrência 16, **3 repetições com aquecimento descartado**. Escrita num nó e leitura imediata em outro.

**Com janela de replicação controlada (120 ms ± 40 ms injetados em cada RPC entre nós):**

| Config | Leituras obsoletas | Escrita p50 | Leitura p50 | Vazão |
|---|---|---|---|---|
| `R=1 W=1` eventual | **43,22%** (dp 1,34) | 47,7 ms | **2,9 ms** | **108 ops/s** |
| `R=1 W=1` causal | **0,00%** (dp 0,00) | 45,7 ms | 132,9 ms | 87 ops/s |
| `R=2 W=2` forte | **0,00%** (dp 0,00) | 138,3 ms | 138,1 ms | 55 ops/s |
| `R=3 W=3` forte | **0,00%** (dp 0,00) | 154,1 ms | 152,7 ms | 51 ops/s |

Três leituras desta tabela:

1. **`R+W > N` elimina leitura obsoleta**, e custa latência nas duas pontas.
2. **O modo causal é o achado mais interessante:** chega a zero obsolescência com escrita tão barata quanto a eventual (45,7 ms contra 47,7 ms), pagando só na leitura. Ele não espera quórum — consulta réplicas até encontrar uma que já tenha visto o contexto do cliente. É a garantia de sessão comprada na ponta certa.
3. **Consistência eventual não é "quase consistente":** 43% das leituras voltaram no tempo.

**Sem latência injetada (cluster ocioso), a mesma bateria:**

| Config | Leituras obsoletas |
|---|---|
| `R=1 W=1` eventual | 1,33% (dp 1,03) |
| demais | 0,00% |

Esse contraste é, ele próprio, um resultado — e está discutido no [relatório](docs/relatorio.md#32-por-que-a-janela-precisa-ser-controlada). Num cluster ocioso a replicação em background termina dentro dos ~48 ms da própria escrita, então a janela de obsolescência fecha antes da leitura acontecer. O modo eventual aparece com ~0% **não por ser consistente, mas porque a janela é curta demais para ser observada**. Medir consistência eventual sem controlar essa janela mede a ociosidade da máquina, não o modelo.

### Tolerância a falhas

| Cenário | Medida | Script |
|---|---|---|
| Queda de nó → recuperação por hinted handoff | **8,19 s** | [demo-queda-de-no.ps1](scripts/demo-queda-de-no.ps1) |
| Convergência sem leitura, por anti-entropia | **5,16 s** | [demo-anti-entropia.ps1](scripts/demo-anti-entropia.ps1) |
| Partição de rede → divergência e siblings | qualitativo | [demo-particao.ps1](scripts/demo-particao.ps1) |

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
              ↕ read repair (síncrono, no caminho de leitura)
              ↕ hinted handoff (substituto segura a escrita)
              ↕ anti-entropia (troca de digest a cada 5 s)
```

| Componente | Arquivo | Papel |
|---|---|---|
| `VersionVector` | [core/VersionVector.java](src/main/java/sd/minidynamo/core/VersionVector.java) | ordem causal parcial; distingue "mais novo" de "concorrente" |
| `Reconciliador` | [core/Reconciliador.java](src/main/java/sd/minidynamo/core/Reconciliador.java) | descarta ancestrais, preserva siblings |
| `HashRing` | [core/HashRing.java](src/main/java/sd/minidynamo/core/HashRing.java) | particionamento, lista de preferência e ordem de substitutos |
| `Store` | [core/Store.java](src/main/java/sd/minidynamo/core/Store.java) | réplica local, em memória |
| `HintStore` | [core/HintStore.java](src/main/java/sd/minidynamo/core/HintStore.java) | escritas retidas em nome de nós fora do ar |
| `Coordenador` | [server/Coordenador.java](src/main/java/sd/minidynamo/server/Coordenador.java) | quórum R/W, read repair, modos, quórum relaxado |
| `HintedHandoff` | [server/HintedHandoff.java](src/main/java/sd/minidynamo/server/HintedHandoff.java) | laço de entrega dos hints |
| `AntiEntropia` | [server/AntiEntropia.java](src/main/java/sd/minidynamo/server/AntiEntropia.java) | convergência periódica por digest |
| `Chaos` | [server/Chaos.java](src/main/java/sd/minidynamo/server/Chaos.java) | partição, latência e perda injetadas via HTTP |
| `Bench` | [bench/Bench.java](src/main/java/sd/minidynamo/bench/Bench.java) | carga + verificador de consistência |

### Por que Java 21 e virtual threads

O coordenador dispara N chamadas às réplicas e espera apenas as W (ou R) primeiras, abandonando o resto. Com `Executors.newVirtualThreadPerTaskExecutor()` uma thread parada esperando resposta custa quase nada:

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

O trabalho mede tempo de recuperação de nó. Os 2–4 s de startup do Spring distorceriam exatamente essa métrica. Javalin sobe em ~300 ms; os 5 containers ficam saudáveis em 6 s. A imagem final tem **309 MB** (`eclipse-temurin:21-jre-alpine` + fat jar de 11 MB).

---

## Os três modelos

| Modo | R/W padrão | Sloppy padrão | Garantia | Preço |
|---|---|---|---|---|
| `forte` | quórum (2 de 3) | **não** | `R+W > N`: leitura cruza com escrita em ≥1 réplica | sem W réplicas vivas, recusa (503) |
| `eventual` | 1 / 1 | sim | responde enquanto houver uma réplica, inclusive nos dois lados de uma partição | 43% de leituras obsoletas na nossa medição |
| `causal` | 1 / 1 + contexto | sim | read-your-writes e monotonic reads sem pagar quórum na escrita | se nenhuma réplica viu o contexto, responde **409** |

O modo causal usa o cabeçalho `X-Contexto` com o vetor que o cliente já viu. O coordenador continua consultando réplicas até achar uma que domine esse contexto.

---

## API

### Cliente

```
PUT    /kv/{chave}?modo=forte|eventual|causal&w=N&sloppy=true|false
GET    /kv/{chave}?modo=...&r=N          [header X-Contexto: {"node1":3}]
DELETE /kv/{chave}?modo=...&w=N
```

Corpo da escrita: `{"valor":"...","contexto":{"node1":3}}` (contexto opcional).

Códigos: `200` ok · `404` chave inexistente · `409` contexto causal não atendido · `503` sem quórum.

### Interna (entre nós)

```
PUT /internal/kv/{chave}             aplica uma versão localmente, sem coordenar
GET /internal/kv/{chave}             versões locais cruas
PUT /internal/hint/{destino}/{chave} guarda escrita em nome de outro nó
GET /internal/digest                 resumo chave→vetor, para anti-entropia
```

### Caos

```
POST /chaos/particao   {"bloqueados":["node3","node4"]}
POST /chaos/latencia   {"ms":120,"jitterMs":40}
POST /chaos/perda      {"probabilidade":0.3}
POST /chaos/curar
GET  /chaos
```

### Diagnóstico

```
GET  /health
GET  /metrics                      formato Prometheus
GET  /debug/anel?chave=x           lista de preferência da chave
GET  /debug/dump                   estado local cru desta réplica
GET  /debug/hints                  hints retidos aqui, por destino
POST /debug/hints/entregar         força a entrega sem esperar o tique
POST /debug/antientropia/{par}     força uma rodada de sincronização
```

`/debug/dump` lê o estado local **sem passar pelo coordenador**, logo não dispara read repair — é o que torna o experimento de anti-entropia válido.

---

## Cenários de falha

### 1. Partição de rede

```powershell
.\scripts\demo-particao.ps1
```

Cluster saudável → partição `{node1,node2} | {node3,node4,node5}` → ambos os lados aceitam escrita com `W=1` e divergem → com `W=3` o lado minoritário é recusado → cura → read repair converge e as escritas concorrentes sobrevivem como **siblings**.

### 2. Queda de nó e hinted handoff

```powershell
.\scripts\demo-queda-de-no.ps1 -NoAlvo node3
```

Com node3 fora, `W=3` **continua fechando**: um substituto (node5) aceita a escrita em nome dele e a retém. A mesma escrita com `sloppy=false` é recusada com 503 — a diferença entre quórum estrito e relaxado, na mesma chave. Quando node3 volta, o hint é entregue em **8,19 s**, sem ninguém ter lido a chave.

### 3. Convergência sem leitura

```powershell
.\scripts\demo-anti-entropia.ps1
```

Isola uma réplica, escreve com `sloppy=false` (zero hints), cura a partição e **nunca lê a chave**. A réplica converge em **5,16 s** pela troca periódica de digest. Prova que a convergência não depende de read repair.

### 4. Latência controlada

```powershell
.\scripts\experimentos.ps1 -LatenciaMs 120 -JitterMs 40
```

---

## O que está implementado

- [x] Hashing consistente com nós virtuais e lista de preferência
- [x] Quóruns R/W configuráveis por requisição; qualquer nó coordena
- [x] Relógios vetoriais, detecção de siblings e reconciliação
- [x] Read repair no caminho de leitura
- [x] **Sloppy quorum e hinted handoff**, com entrega em segundo plano
- [x] **Anti-entropia por troca de digest**, independente de leitura
- [x] Tombstones para remoção
- [x] Injeção de partição, latência e perda via HTTP
- [x] Métricas Prometheus com percentis
- [x] Bench com verificação de obsolescência por relógio vetorial
- [x] 35 testes unitários do núcleo

## O que ficou de fora

- [ ] **Árvore de Merkle** — a anti-entropia compara digest linear no número de chaves. O Dynamo usa Merkle para que a comparação seja logarítmica. Na escala deste trabalho (dezenas de chaves) a diferença não aparece; num cluster real, apareceria.
- [ ] **Membership dinâmico** — a topologia vem fixa de `PEERS`; entrar ou sair de nó exige reiniciar o cluster.
- [ ] **Merge de carrinho de compras** — os valores são strings opacas. Um carrinho (união de itens) faria o merge de siblings ter significado, que é o exemplo canônico do paper.
- [ ] **Persistência** — tudo em memória; reiniciar um nó zera sua réplica. É o que torna o hinted handoff visível na demo, mas não serviria em produção.

---

## Limitações que vão no relatório

**`R+W > N` não dá linearizabilidade.** Dá consistência de quórum. As escritas não são atômicas entre réplicas: um 503 por falta de quórum **não** desfaz o que já gravou nas réplicas que responderam. Quem precisa de linearizabilidade usa consenso (Raft/Paxos), não quórum.

**Quórum relaxado quebra a interseção de quóruns.** Com hinted handoff, as réplicas que confirmaram a escrita podem não ser as da lista de preferência — então uma leitura com R réplicas da lista pode não cruzar com a escrita, e `R+W > N` deixa de garantir o que promete. Por isso o modo `forte` tem `sloppy=false` por padrão e os modos `eventual` e `causal` têm `sloppy=true`: disponibilidade e consistência estão sendo escolhidas explicitamente, não por acidente. O parâmetro `?sloppy=` permite demonstrar os dois lados na mesma chave.

**Coordenador fora da lista de preferência faz escrita cega.** Se o coordenador não é réplica daquela chave, não conhece o relógio atual e parte do vetor vazio, o que gera sibling sob concorrência. No Dynamo real o cliente roteia para um nó da lista.

**A injeção de caos simula perda de mensagem, não queda de processo.** `/chaos/particao` mantém o processo vivo com estado; `docker compose stop` mata o processo, que volta vazio.

**Medição em máquina única.** 5 containers e o bench competindo pelos mesmos núcleos. Os números de obsolescência são lógicos (dominância de vetor) e confiáveis; os de latência medem um cluster co-residente.

---

## Divisão do trabalho

| Quem | Frente |
|---|---|
| Luiz Fernando | núcleo: anel, relógios vetoriais, coordenador de quórum |
| Dyogo | infraestrutura e caos: compose, cenários de falha, hinted handoff, anti-entropia |
| Gustavo | bench, verificador de consistência, métricas e gráficos, documento técnico |

## Documentos

- [Estudo do tema](docs/estudo-tema.md) — conceitos, estado da arte, problemas em aberto
- [Levantamento de ferramentas](docs/ferramentas.md) — o que existe e como se compara
- [Relatório técnico](docs/relatorio.md) — arquitetura, decisões, experimentos, limitações
- [Roteiro do seminário](docs/seminario.md) — 25 min com demo ao vivo

## Referências

- DeCandia et al. **Dynamo: Amazon's Highly Available Key-value Store.** SOSP 2007.
- Vogels, W. **Eventually Consistent.** Communications of the ACM, 2009.
- Lamport, L. **Time, Clocks, and the Ordering of Events in a Distributed System.** CACM, 1978.
- Abadi, D. **Consistency Tradeoffs in Modern Distributed Database System Design (PACELC).** IEEE Computer, 2012.
- Bailis & Ghodsi. **Eventual Consistency Today: Limitations, Extensions, and Beyond.** ACM Queue, 2013.
- Apache Cassandra — [documentação de consistência ajustável](https://cassandra.apache.org/doc/latest/cassandra/architecture/dynamo.html)
