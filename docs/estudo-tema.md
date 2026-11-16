# Replicação e modelos de consistência: forte, eventual, causal e quóruns no estilo Dynamo

**Trabalhos 5 e 6 — Sistemas Distribuídos, 2026**
Luiz Fernando Luth Junior · Dyogo Romagna · Gustavo de Almeida Neves

---

## 1. O problema

Replicar dados em vários nós resolve três coisas de uma vez: sobrevive à perda de uma máquina, serve leituras de onde o usuário está, e distribui carga. O custo é um só, mas é profundo: **as réplicas podem discordar entre si**, e decidir o que fazer com essa discordância é o que define o sistema.

O caso que torna o problema inevitável é a partição de rede. Quando metade do cluster não fala com a outra metade, duas escritas conflitantes podem ser aceitas, uma de cada lado, e nenhum dos lados tem como saber. A partir daí só restam duas saídas: recusar escritas de um dos lados (perdendo disponibilidade) ou aceitar as duas e resolver depois (perdendo consistência imediata).

### 1.1 CAP, e por que a leitura popular dele é ruim

O teorema CAP, conjecturado por Brewer (2000) e provado por Gilbert e Lynch (2002), afirma que um sistema distribuído não pode ter simultaneamente Consistência (linearizabilidade), Disponibilidade e tolerância a Partição.

A leitura popular — "escolha 2 de 3" — é enganosa por dois motivos:

1. **Partição não é escolha.** Redes particionam. Um sistema distribuído real tem que tolerar P; a escolha é só entre C e A.
2. **A escolha só se aplica durante a partição.** Fora dela não há trade-off entre C e A. Brewer voltou ao assunto em *CAP Twelve Years Later: How the "Rules" Have Changed* (2012) justamente para corrigir essa leitura.

### 1.2 PACELC: o trade-off que existe o tempo todo

Abadi (2012) propôs uma formulação mais útil:

> **se** há Partição (P), escolha entre Disponibilidade (A) e Consistência (C);
> **senão** (E, *else*), escolha entre Latência (L) e Consistência (C).

O segundo ramo é o que importa no dia a dia: mesmo com a rede perfeita, exigir que mais réplicas confirmem uma operação custa tempo. Nosso experimento mede exatamente esse ramo "ELC" — e o resultado está na seção 6.

Na classificação de Abadi, Dynamo e Cassandra são **PA/EL** (preferem disponibilidade na partição e latência fora dela), enquanto Spanner e sistemas baseados em consenso são **PC/EC**.

---

## 2. O espectro de modelos de consistência

Um modelo de consistência é um **contrato** entre o sistema e o programador: dado um conjunto de operações concorrentes, quais resultados a aplicação pode observar. Do mais forte ao mais fraco:

### 2.1 Linearizabilidade

Herlihy e Wing (1990). Cada operação parece acontecer instantaneamente em algum ponto entre seu início e seu fim, e a ordem respeita o tempo real: se a operação A terminou antes de B começar, todo mundo vê A antes de B.

É o modelo mais forte e o mais caro. Exige consenso — e, portanto, maioria viva e pelo menos um round-trip de coordenação.

### 2.2 Consistência sequencial

Lamport (1979). Existe **uma** ordem total das operações, consistente com a ordem de programa de cada processo, mas **não necessariamente com o tempo real**. Mais fraca que linearizabilidade: uma leitura pode devolver um valor antigo, desde que todos concordem com a mesma história.

### 2.3 Consistência causal

Ahamad et al. (1995). Operações causalmente relacionadas são vistas por todos na mesma ordem; operações concorrentes podem ser vistas em ordens diferentes por nós diferentes.

Causalidade aqui é o "happens-before" de Lamport (1978): A precede B se A e B são do mesmo processo e A veio antes, ou se B leu o que A escreveu, ou por transitividade.

Dois resultados tornam esse modelo central:

- **É o modelo mais forte compatível com disponibilidade total** sob partição (Mahajan, Alvisi e Dahlin, 2011). Qualquer coisa mais forte que causal obriga a sacrificar disponibilidade.
- **É implementável com custo modesto** — COPS (Lloyd et al., SOSP 2011) mostrou consistência causal com escalabilidade horizontal.

### 2.4 Garantias de sessão

Terry et al. (1994) propuseram garantias mais fracas que causal, mas que resolvem a maior parte do desconforto do usuário:

| Garantia | O que promete |
|---|---|
| **Read-your-writes** | você sempre lê pelo menos o que você mesmo escreveu |
| **Monotonic reads** | você nunca vê o estado "andar para trás" |
| **Writes-follow-reads** | sua escrita só aparece depois do que você leu antes dela |
| **Monotonic writes** | suas escritas são aplicadas na ordem em que você as fez |

**É isto que o modo `causal` da nossa implementação oferece**: o cliente carrega o vetor do que já viu, e o coordenador se recusa a responder com estado anterior a esse contexto.

### 2.5 Consistência eventual

Vogels (2009): na ausência de novas escritas, todas as réplicas eventualmente convergem para o mesmo valor.

A definição é notoriamente fraca — "eventualmente" não tem prazo, e nada é prometido enquanto isso. Bailis e Ghodsi (*Eventual Consistency Today*, ACM Queue 2013) argumentam que a pergunta útil não é *se* converge, mas **quão obsoleto é o dado enquanto não converge**, em tempo e em versões. Nossa medição de 43,22% de leituras obsoletas com janela de 120 ms é exatamente uma resposta quantitativa a essa pergunta.

---

## 3. Mecanismos

### 3.1 Relógios lógicos e vetoriais

Lamport (1978) mostrou que timestamp de relógio de parede não serve para ordenar eventos distribuídos: relógios de máquinas diferentes divergem, e a diferença pode ser maior que o intervalo entre dois eventos.

Relógios lógicos escalares dão uma ordem total, mas não permitem **detectar** concorrência. Relógios vetoriais (Mattern 1988; Fidge 1988) resolvem isso: um contador por nó, comparados ponto a ponto. O resultado é uma ordem **parcial** — e é justamente o caso "incomparável" que interessa, porque identifica escritas concorrentes.

```
A = {node1:2, node2:1}   B = {node1:1, node2:3}
A tem node1 maior, B tem node2 maior  →  CONCORRENTES
```

**Problema em aberto:** o vetor cresce com o número de nós que já escreveram na chave. Preguiça et al. (2010) propuseram *dotted version vectors* para limitar esse crescimento quando há muitos clientes. Cassandra evita o problema abandonando vetores e usando last-writer-wins por célula — e aceitando a perda silenciosa que isso implica.

### 3.2 Quóruns

Gifford (1979) e Thomas (1979). Com N réplicas, exija W confirmações para escrever e R para ler. Se

```
R + W > N
```

então todo conjunto de leitura intersecta todo conjunto de escrita em pelo menos uma réplica, e a leitura enxerga a escrita mais recente.

**O que essa condição NÃO garante:** linearizabilidade. Faltam duas coisas. Primeiro, as escritas não são atômicas entre réplicas — uma escrita que falhou o quórum pode ter gravado em algumas réplicas e não é desfeita. Segundo, duas leituras concorrentes podem devolver valores diferentes se caírem em conjuntos distintos durante uma escrita em curso. Quórum dá "consistência de quórum", não consenso.

### 3.3 Hashing consistente

Karger et al. (1997). Nós e chaves são mapeados no mesmo espaço circular; cada chave pertence ao primeiro nó em sentido horário. A propriedade que importa: entrar ou sair um nó realoca apenas **O(K/N)** chaves, não todas.

**Nós virtuais** corrigem o desbalanceamento: cada nó físico aparece várias vezes no anel. Com um único ponto por nó, a variância é alta — um nó pode ficar com 40% do espaço. Nossa implementação usa 128 vnodes por nó e [um teste verifica](../src/test/java/sd/minidynamo/core/HashRingTest.java) que o desvio fica abaixo de 25% do ideal.

### 3.4 Sloppy quorum e hinted handoff

Dynamo §4.6. Se uma réplica da lista de preferência está fora, a escrita não falha: vai para o próximo nó do anel, acompanhada da "dica" de quem era o dono. O substituto retém o dado — **sem servi-lo em leituras** — e o entrega quando o dono volta.

O ganho é disponibilidade de escrita durante falha. O preço é conceitualmente sério e costuma ser subestimado: **com substitutos, `R+W > N` deixa de garantir interseção**, porque as réplicas que confirmaram podem não ser as da lista de preferência. É por isso que nossa implementação deixa isso explícito num parâmetro (`?sloppy=`) em vez de ligá-lo em silêncio.

### 3.5 Read repair e anti-entropia

**Read repair:** ao reconciliar uma leitura, o coordenador reenvia o resultado às réplicas atrasadas. É barato (a ida já foi paga) e conserta no caminho quente.

Sua insuficiência é estrutural: **conserta apenas o que é lido**. Uma chave que ninguém lê fica divergente indefinidamente.

**Anti-entropia** fecha essa lacuna com sincronização periódica em segundo plano. Dynamo §4.7 usa **árvores de Merkle**: cada réplica mantém uma árvore de hashes do seu intervalo de chaves; duas réplicas comparam as raízes e só descem nos ramos que diferem, o que torna a comparação logarítmica. Nossa implementação troca digest linear — suficiente na escala do trabalho, e registrado como limitação.

### 3.6 Tombstones

Remoção em sistema eventual não pode apagar o registro: a réplica que estava fora voltaria a propagar o valor antigo. A remoção precisa ser uma **versão** que vença a anterior — um tombstone.

Isso cria o problema de quando coletar os tombstones. Coletar cedo demais ressuscita dados; tarde demais vaza espaço. Cassandra usa `gc_grace_seconds` (padrão 10 dias) e exige reparo nesse intervalo.

### 3.7 CRDTs: a alternativa

Shapiro et al. (2011). Tipos de dados cuja operação de merge é associativa, comutativa e idempotente — logo, réplicas convergem **por construção**, sem expor conflito à aplicação.

A diferença de filosofia vale explicitar: o Dynamo **expõe** o conflito e delega a resolução; o CRDT **elimina** o conflito escolhendo uma estrutura cuja fusão é sempre bem definida. O preço do CRDT é que nem toda semântica cabe num CRDT, e alguns carregam metadados consideráveis.

---

## 4. Estudo de caso: Dynamo

DeCandia et al., SOSP 2007. Construído para o carrinho de compras da Amazon, com um requisito acima de todos: **a escrita nunca pode ser rejeitada**. Um "adicionar ao carrinho" que falha é receita perdida.

Decisões que decorrem disso:

| Decisão | Consequência |
|---|---|
| DHT de zero saltos | cada nó conhece todo o anel; sem salto intermediário |
| Qualquer nó coordena | sem mestre, sem ponto único de falha |
| N, R, W por operação | a aplicação escolhe o ponto no trade-off |
| Vetores de versão | conflitos são detectados, não mascarados |
| Reconciliação na leitura | a aplicação resolve o conflito, não o banco |
| Sloppy quorum | escrita sobrevive à queda de réplica |
| Gossip para membership | sem coordenador central |

**O exemplo do carrinho** é o coração do argumento. Se duas sessões concorrentes adicionam itens diferentes, last-writer-wins descartaria uma das adições — o cliente veria um item sumir do carrinho. Com siblings, a aplicação faz a união dos itens. O pior caso vira "um item removido reaparece", que é preferível a "um item adicionado desaparece".

### 4.1 Linhagem

Dynamo → **Cassandra** (quóruns + LWW, modelo de colunas), **Riak** (o mais fiel: vetores e siblings expostos), **Voldemort** (LinkedIn), **DynamoDB**.

Vale registrar que o **DynamoDB serviço ≠ Dynamo paper**. Elhemali et al. (USENIX ATC 2022) descrevem um sistema bastante diferente: multi-tenant, com Paxos para eleição de líder por partição e leituras fortes opcionais. O nome permaneceu; a arquitetura mudou.

---

## 5. Aplicações

### Onde consistência eventual é adequada

Carrinho de compras, contadores de visualização, feeds sociais, cache distribuído, DNS, catálogo de produtos, sessões de usuário. O critério comum: **o custo de resolver um conflito depois é menor que o custo da indisponibilidade**.

### Onde não é

Saldo bancário, reserva de assento, controle de estoque com unidade limitada, locks distribuídos, sequências únicas. Aqui o conflito não é resolvível depois — vender o mesmo assento duas vezes não se conserta com merge.

### A pergunta prática

> Se duas operações concorrentes produzirem estados divergentes, **existe uma regra de merge que a aplicação aceite?**

Se sim, consistência eventual com siblings serve, e é mais barata e mais disponível. Se não, é consenso.

---

## 6. Resultados do nosso experimento

Implementamos um armazenamento chave-valor replicado (o [MiniDynamo](../README.md)) em que o modelo de consistência é parâmetro de requisição. Com N=3, 300 operações, 3 repetições e janela de replicação controlada em 120 ms ± 40 ms:

| Config | Leituras obsoletas | Escrita p50 | Leitura p50 |
|---|---|---|---|
| `R=1 W=1` eventual | **43,22%** (dp 1,34) | 47,7 ms | 2,9 ms |
| `R=1 W=1` causal | 0,00% | 45,7 ms | 132,9 ms |
| `R=2 W=2` forte | 0,00% | 138,3 ms | 138,1 ms |
| `R=3 W=3` forte | 0,00% | 154,1 ms | 152,7 ms |

Três observações ligam a teoria aos números:

1. **O ramo ELC do PACELC é visível e caro.** Sem partição alguma, exigir quórum triplicou a latência de escrita (47,7 → 154,1 ms).
2. **Consistência causal compra a garantia na ponta certa.** Escrita tão barata quanto a eventual, custo concentrado na leitura, zero obsolescência. É o resultado prático de Mahajan et al. (2011): dá para ter a garantia mais forte compatível com disponibilidade sem pagar quórum na escrita.
3. **"Eventual" significa mesmo eventual.** 43% das leituras voltaram no tempo com uma janela de apenas 120 ms.

Detalhes de método, incluindo por que a janela precisa ser controlada, no [relatório](relatorio.md).

---

## 7. Limitações e problemas em aberto

**Crescimento dos metadados de versão.** Vetores crescem com o número de escritores. Dotted version vectors mitigam, não eliminam.

**Coleta de tombstones.** Sem garantia de reparo dentro da janela, remoções ressuscitam.

**Explosão de siblings.** Concorrência alta sobre a mesma chave sem reconciliação produz muitos siblings. O Dynamo relata que 99,94% das requisições viram uma única versão — mas o caso ruim existe.

**Testar consistência é difícil.** Violações são raras, dependem de temporização e não aparecem em teste determinístico. O trabalho do Jepsen mostrou violações em dezenas de sistemas que afirmavam não tê-las. Nosso verificador é mínimo: detecta obsolescência por dominância de vetor, não linearizabilidade.

**Transações entre partições.** Quórum não resolve atomicidade multi-chave. Spanner, Calvin e Percolator atacam isso por caminhos diferentes.

**Consistência causal com metadados enxutos.** Continua ativo na literatura: rastrear causalidade exatamente custa metadados que crescem; aproximar perde garantias.

---

## 8. Estado da arte

**Relógios híbridos (HLC)** — Kulkarni et al. (2014) combinam relógio físico e lógico num timestamp que é compacto e próximo do tempo real. Usados em CockroachDB e MongoDB.

**TrueTime e Spanner** — Corbett et al. (OSDI 2012). Com GPS e relógios atômicos, o Google limita a incerteza do relógio a poucos milissegundos e espera esse intervalo antes de confirmar. Transforma sincronia de relógio em garantia de consistência — ao preço de hardware dedicado.

**CRDTs em produção** — Redis Enterprise, Riak, Automerge e Yjs. O movimento **local-first** (Kleppmann et al., 2019) propõe aplicações que funcionam offline por padrão, com CRDTs fazendo a convergência.

**NewSQL** — CockroachDB, TiDB, YugabyteDB oferecem SQL serializável sobre Raft por intervalo de chaves. A aposta é que consenso ficou barato o suficiente.

**Verificação** — Jepsen, Elle e Porcupine tornaram rotina checar as afirmações de consistência dos fornecedores.

---

## 9. Conclusão

O que o estudo e o experimento deixam claro é que **não existe o melhor modelo de consistência, existe o modelo adequado ao dado**. O mérito da abordagem do Dynamo é tornar essa escolha explícita e granular: a mesma infraestrutura serve o carrinho de compras com `R=W=1` e o controle de estoque com quórum, e o programador vê o preço de cada escolha.

O que nossa implementação acrescentou à compreensão do grupo foi o custo real de cada mecanismo. Hinted handoff parece gratuito até perceber que ele **quebra a interseção de quóruns** — a propriedade que justifica `R+W > N`. Read repair parece suficiente até perceber que ele só conserta o que é lido. E a consistência eventual parece inofensiva até medi-la: 43% das leituras obsoletas com uma janela de 120 ms.

---

## Referências

ABADI, D. Consistency Tradeoffs in Modern Distributed Database System Design. **IEEE Computer**, v. 45, n. 2, p. 37-42, 2012.

AHAMAD, M. et al. Causal memory: definitions, implementation, and programming. **Distributed Computing**, v. 9, n. 1, p. 37-49, 1995.

BAILIS, P.; GHODSI, A. Eventual Consistency Today: Limitations, Extensions, and Beyond. **ACM Queue**, v. 11, n. 3, 2013.

BREWER, E. CAP Twelve Years Later: How the "Rules" Have Changed. **IEEE Computer**, v. 45, n. 2, p. 23-29, 2012.

CORBETT, J. C. et al. Spanner: Google's Globally-Distributed Database. In: **OSDI**, 2012.

DECANDIA, G. et al. Dynamo: Amazon's Highly Available Key-value Store. In: **SOSP**, 2007.

ELHEMALI, M. et al. Amazon DynamoDB: A Scalable, Predictably Performant, and Fully Managed NoSQL Database Service. In: **USENIX ATC**, 2022.

FIDGE, C. J. Timestamps in message-passing systems that preserve the partial ordering. In: **Australian Computer Science Conference**, 1988.

GIFFORD, D. K. Weighted voting for replicated data. In: **SOSP**, 1979.

GILBERT, S.; LYNCH, N. Brewer's conjecture and the feasibility of consistent, available, partition-tolerant web services. **ACM SIGACT News**, v. 33, n. 2, p. 51-59, 2002.

HERLIHY, M. P.; WING, J. M. Linearizability: a correctness condition for concurrent objects. **ACM TOPLAS**, v. 12, n. 3, p. 463-492, 1990.

KARGER, D. et al. Consistent hashing and random trees. In: **STOC**, 1997.

KLEPPMANN, M. et al. Local-first software: you own your data, in spite of the cloud. In: **Onward!**, 2019.

KULKARNI, S. et al. Logical Physical Clocks and Consistent Snapshots in Globally Distributed Databases. In: **OPODIS**, 2014.

LAMPORT, L. Time, Clocks, and the Ordering of Events in a Distributed System. **CACM**, v. 21, n. 7, p. 558-565, 1978.

LAMPORT, L. How to Make a Multiprocessor Computer That Correctly Executes Multiprocess Programs. **IEEE Transactions on Computers**, C-28, n. 9, 1979.

LLOYD, W. et al. Don't Settle for Eventual: Scalable Causal Consistency for Wide-Area Storage with COPS. In: **SOSP**, 2011.

MAHAJAN, P.; ALVISI, L.; DAHLIN, M. **Consistency, Availability, and Convergence.** Technical Report TR-11-22, University of Texas at Austin, 2011.

MATTERN, F. Virtual Time and Global States of Distributed Systems. In: **Workshop on Parallel and Distributed Algorithms**, 1988.

PREGUIÇA, N. et al. Dotted Version Vectors: Logical Clocks for Optimistic Replication. **arXiv:1011.5808**, 2010.

SHAPIRO, M. et al. **Conflict-free Replicated Data Types.** INRIA Research Report RR-7687, 2011.

TERRY, D. B. et al. Session guarantees for weakly consistent replicated data. In: **PDIS**, 1994.

THOMAS, R. H. A majority consensus approach to concurrency control for multiple copy databases. **ACM TODS**, v. 4, n. 2, p. 180-209, 1979.

VOGELS, W. Eventually Consistent. **CACM**, v. 52, n. 1, p. 40-44, 2009.
