# Relatório técnico — MiniDynamo

**Trabalhos 5 e 6 — Sistemas Distribuídos, 2026**
Luiz Fernando Luth Junior · Dyogo Romagna · Gustavo de Almeida Neves

---

## 1. Arquitetura

O MiniDynamo é um armazenamento chave-valor replicado em que **o modelo de consistência é parâmetro de requisição**. Cinco nós idênticos; nenhum é mestre.

```
cliente ──HTTP──> qualquer nó  (todos coordenam)
                      │
             hashing consistente (128 vnodes/nó)
                      │
              lista de preferência: N réplicas
                      │
     node1   node2   node3   node4   node5
              ↕ read repair      (síncrono, no caminho de leitura)
              ↕ hinted handoff   (substituto segura a escrita)
              ↕ anti-entropia    (troca de digest a cada 5 s)
```

### 1.1 Fluxo de uma escrita

1. O cliente faz `PUT /kv/carrinho:42?modo=forte&w=2` em **qualquer** nó.
2. Esse nó torna-se coordenador. Calcula `hash(chave)` e caminha no anel para obter a lista de preferência — digamos `[node3, node1, node4]`.
3. Datação da versão. Se o cliente enviou contexto (vetor de uma leitura anterior), parte dele; senão parte do que o coordenador conhece localmente. Incrementa o próprio contador: `{node3:1}` → `{node3:2}`.
4. Grava localmente se for réplica, e dispara os RPCs às demais em threads virtuais.
5. Conta os acks. Ao atingir W, responde ao cliente. **As tarefas restantes não são canceladas** — essa propagação tardia é a consistência eventual.
6. Se uma réplica falha e `sloppy=true`, o coordenador procura um substituto no anel e entrega a escrita com a dica de quem era o dono. Esse ack conta para W.
7. Se W não é atingido, responde 503. **O que já foi gravado não é desfeito.**

### 1.2 Fluxo de uma leitura

1. `GET /kv/carrinho:42?modo=forte&r=2`, com `X-Contexto` opcional.
2. Coordenador consulta as réplicas da lista de preferência em paralelo.
3. Ao reunir R respostas, reconcilia: descarta toda versão que seja ancestral de outra. O que sobra é uma versão vencedora ou vários **siblings**.
4. **No modo causal**, R respostas podem não bastar: se nenhuma réplica consultada domina o contexto do cliente, o coordenador segue consultando as restantes. Se esgotar sem encontrar, responde **409** em vez de voltar no tempo.
5. **Read repair:** réplicas que responderam com estado incompleto recebem o conjunto reconciliado, em segundo plano.
6. Responde com os valores, o contexto resumido e metadados de diagnóstico (réplicas ok, falhas, conflito, duração).

---

## 2. Decisões de projeto

| Decisão | Alternativa descartada | Motivo |
|---|---|---|
| Java 21 + Javalin | Spring Boot | mede-se tempo de recuperação; 2–4 s de startup distorceriam a métrica e inflariam 5 containers. Javalin sobe em ~300 ms |
| Virtual threads no fan-out | pool de threads dimensionado | esperar as W primeiras de N respostas fica trivial com `ExecutorCompletionService` sobre threads virtuais |
| Siblings expostos ao cliente | last-writer-wins | LWW descarta escrita concorrente em silêncio. Preferimos devolver conflito a perder dado |
| Relógios vetoriais | timestamp de parede | relógios de máquinas divergem; a ordenação precisa ser lógica |
| Injeção de caos no processo | Toxiproxy / `tc netem` | equipe no Windows; `netem` exigiria `NET_ADMIN` no container e não se comportaria igual nas três máquinas |
| Armazenamento em memória | disco | o tema é consistência, não durabilidade. E o nó volta vazio, o que torna o hinted handoff **visível** na demo |
| `sloppy` explícito por requisição | sempre ligado (como no Dynamo) | quórum relaxado quebra a interseção de quóruns; deixar isso implícito esconderia a perda de garantia |
| 128 vnodes por nó | 1 ponto por nó | com um ponto a variância de carga é alta. Teste verifica desvio < 25% do ideal |
| Digest linear na anti-entropia | árvore de Merkle | Merkle é logarítmico e é o certo em escala; na escala deste trabalho o digest é suficiente e muito mais simples de explicar |

---

## 3. Metodologia dos experimentos

### 3.1 Como a obsolescência é detectada

Cada operação do bench é: **escrever num nó, ler imediatamente de outro nó**. É o padrão mais hostil para consistência eventual, e é o que produz o número de interesse.

A detecção **não compara valores**. Compara relógios vetoriais: se o contexto devolvido pela leitura não domina o contexto que a escrita confirmou, aquela leitura voltou no tempo. Comparar strings daria falso negativo sempre que duas escritas gravassem o mesmo texto.

Duas decisões de método importam para a validade:

- **Só se avalia leitura que o sistema serviu.** Um 503 (sem quórum) não é violação de consistência — é indisponibilidade. A primeira versão do bench contava 503 como obsolescência e produziu "5,59% de obsoletas no modo forte", impossível sob `R+W > N`. A coluna `leituras_avaliadas` deixa o denominador explícito.
- **Concorrência limitada (16 em voo).** Sem limite, as operações partem juntas, enfileiram nos nós, e o p95 medido passa a ser espera na fila do bench. A primeira versão media p50 de 1.563 ms em localhost por esse motivo.

### 3.2 Por que a janela precisa ser controlada

Este é o ponto metodológico central do trabalho, e foi descoberto pelo caminho.

Rodando contra o cluster Docker **ocioso**, o modo eventual apresentou apenas **1,33%** de leituras obsoletas. Não porque seja consistente: porque a replicação em background terminava dentro dos ~48 ms da própria escrita. Quando a leitura acontecia, todas as réplicas já tinham o dado. **A janela de obsolescência fechava antes de poder ser observada.**

Medir consistência eventual assim mede a ociosidade da máquina, não o modelo — e dá um número que muda conforme a carga do notebook de quem roda.

A correção foi injetar latência controlada em cada RPC entre nós (`/chaos/latencia`, 120 ms ± 40 ms) antes das rodadas. Com a janela controlada, o número vira **43,22% com desvio padrão de 1,34** entre três repetições: reprodutível e explicável.

### 3.3 Controles aplicados

- Rodada de **aquecimento descartada** (JIT da JVM, cache de conexão HTTP).
- **3 repetições** de cada configuração, com média e desvio padrão.
- **Ordem alternada** entre repetições, para que a primeira configuração não pague sozinha o aquecimento residual.
- Resultados anexados em `resultados/resultados.csv` para agregação independente.

O efeito do aquecimento não é teórico: sem ele, `R=3 W=3` aparecia **mais rápido** que `R=2 W=2`, invertendo a conclusão.

---

## 4. Resultados

### 4.1 Obsolescência e latência por configuração

N=3, 300 operações, 30 chaves, concorrência 16, 3 repetições, janela de 120 ms ± 40 ms.

| Config | Leituras obsoletas | dp | Escrita p50 | Leitura p50 | Vazão |
|---|---|---|---|---|---|
| `R=1 W=1` eventual | **43,22%** | 1,34 | 47,7 ms | **2,9 ms** | **108 ops/s** |
| `R=1 W=1` causal | **0,00%** | 0,00 | 45,7 ms | 132,9 ms | 87 ops/s |
| `R=2 W=2` forte | **0,00%** | 0,00 | 138,3 ms | 138,1 ms | 55 ops/s |
| `R=3 W=3` forte | **0,00%** | 0,00 | 154,1 ms | 152,7 ms | 51 ops/s |

**Interpretação:**

**`R+W > N` cumpre o que promete.** Zero obsolescência em todas as configurações de quórum, em todas as repetições. A condição de interseção não é teoria: é observável.

**O custo é o ramo ELC do PACELC.** Sem partição alguma, exigir quórum levou a escrita de 47,7 ms para 154,1 ms — 3,2×. A vazão caiu de 108 para 51 ops/s. Esse é o trade-off que o CAP não descreve e que domina a operação real.

**O modo causal é o resultado mais interessante.** Zero obsolescência com escrita **tão barata quanto a eventual** (45,7 vs 47,7 ms), concentrando o custo na leitura (132,9 ms). Ele não espera quórum: consulta réplicas até achar uma que já tenha visto o contexto do cliente. Na prática, isso realiza o resultado de Mahajan et al. (2011) — a garantia mais forte compatível com disponibilidade, sem pagar quórum na escrita.

Para uma carga com muitas escritas e leituras tolerantes, causal domina `R=2 W=2`: mesma garantia de sessão, escrita 3× mais rápida.

**"Eventual" significa eventual.** 43% das leituras voltaram no tempo com janela de 120 ms. Em troca: a leitura mais rápida da tabela (2,9 ms — serve da réplica local sem esperar ninguém) e a maior vazão.

### 4.2 Queda de nó e hinted handoff

Procedimento: [demo-queda-de-no.ps1](../scripts/demo-queda-de-no.ps1). Escolhe uma chave cuja lista de preferência contenha o nó alvo, derruba o container, escreve, sobe de volta.

| Observação | Resultado |
|---|---|
| Lista de preferência da chave | `node3, node1, node4` |
| Escrita `W=3` com node3 fora, `sloppy=true` | **aceita** — `ok=3/3`, hint `node3 via node5` |
| Hints retidos no cluster | 1, em node5, destinado a node3 |
| Escrita `W=3` com node3 fora, `sloppy=false` | **recusada** — HTTP 503 |
| **Tempo de recuperação** | **8,19 s** (do `docker compose start` ao último hint entregue) |
| node3 recuperou o dado? | **Sim**, sem ninguém ter lido a chave |

A comparação `sloppy=true` vs `sloppy=false` **na mesma chave e na mesma falha** é a demonstração mais limpa do trade-off: o primeiro mantém disponibilidade de escrita ao custo da interseção de quóruns; o segundo preserva a garantia e recusa.

Os 8,19 s decompõem-se em: startup do container (~1 s), healthcheck, e o laço de entrega de hints que roda a cada 2 s. É configurável por `HINT_DELIVERY_MS` — um intervalo menor reduz o tempo de recuperação e aumenta o tráfego de fundo.

### 4.3 Convergência sem leitura (anti-entropia)

Procedimento: [demo-anti-entropia.ps1](../scripts/demo-anti-entropia.ps1). O desenho do experimento é o que o torna conclusivo:

1. Isola uma réplica da chave, nos dois sentidos.
2. Escreve com **`sloppy=false`**, garantindo que **nenhum hint** seja criado — verificado no script. Assim a recuperação não pode vir do handoff.
3. Cura a partição.
4. **Nunca lê a chave pelo coordenador** — só inspeciona `/debug/dump`, que lê o estado local cru e não dispara read repair.

| Observação | Resultado |
|---|---|
| Réplicas da chave | `node4, node5, node2` — isolada: `node2` |
| Hints no cluster após a escrita | **0** (confirmado) |
| Estado de node2 antes da cura | vazio |
| **Tempo até convergir** | **5,16 s** |
| Estado final das três réplicas | idêntico |

Com rodadas a cada 5 s, 5,16 s significa que a convergência ocorreu **na primeira rodada após a cura**. Prova que a convergência não depende de read repair — que era a lacuna apontada na primeira versão do projeto.

### 4.4 Partição de rede

Procedimento: [demo-particao.ps1](../scripts/demo-particao.ps1). Partição `{node1,node2} | {node3,node4,node5}`.

Estado local das réplicas durante a partição, com escritas `W=1` nos dois lados:

```
node1 : escrito-pela-minoria @ {"node1":2}
node3 : estado-inicial @ {"node1":1}  ;  escrito-pela-maioria @ {"node5":1}
node4 : estado-inicial @ {"node1":1}  ;  escrito-pela-maioria @ {"node5":1}
```

Os vetores `{"node1":2}` e `{"node5":1}` são **concorrentes** — nenhum viu o outro. Com `W=3`, o lado minoritário recebeu 503: preferiu recusar a divergir.

Após a cura, a leitura com quórum disparou read repair e as duas escritas sobreviveram como **siblings**. O sistema não escolheu por timestamp — devolveu o conflito. Num carrinho de compras, o merge seria a união dos itens; LWW teria descartado uma das escritas em silêncio.

### 4.5 Distribuição de carga do anel

Verificado por teste automatizado ([HashRingTest](../src/test/java/sd/minidynamo/core/HashRingTest.java)) com 20.000 chaves e 5 nós: desvio de cada nó abaixo de **25% do ideal** (4.000 chaves). Com um único ponto por nó em vez de 128 vnodes, esse desvio seria muito maior.

Um segundo teste verifica a propriedade de hashing consistente: ao remover um nó de 5, **menos de 30% das chaves** trocam de dono — próximo do 1/5 teórico, e não as 100% que um `hash % N` provocaria.

### 4.6 Métricas expostas

Em `/metrics`, formato Prometheus, com percentis calculados no nó:

`minidynamo_leitura` · `minidynamo_escrita` (histogramas por modo) · `minidynamo_quorum_falhou` · `minidynamo_conflitos` · `minidynamo_read_repair` · `minidynamo_hints_criados` · `minidynamo_hints_entregues` · `minidynamo_antientropia_chaves_sincronizadas` · `minidynamo_rpc_falhou`

---

## 5. Validação por testes

35 testes unitários cobrindo o núcleo:

| Suíte | O que verifica |
|---|---|
| `VersionVectorTest` (7) | descendência, concorrência, união, imutabilidade |
| `ReconciliadorTest` (6) | descarte de ancestrais, preservação de siblings, **independência da ordem de chegada**, idempotência |
| `StoreTest` (7) | sobrescrita, rejeição de versão atrasada, siblings, tombstone |
| `HashRingTest` (8) | N nós distintos, determinismo, uniformidade, realocação O(K/N), ordem de substitutos |
| `HintStoreTest` (7) | retenção, idempotência, entrega, contabilidade |

O teste de independência de ordem merece destaque: réplicas respondem em ordem arbitrária, e o resultado da reconciliação **não pode** depender disso. É uma propriedade que seria fácil violar sem perceber.

---

## 6. Limitações

**`R+W > N` não dá linearizabilidade.** Dá consistência de quórum. As escritas não são atômicas entre réplicas: um 503 por falta de quórum **não desfaz** o que já gravou nas réplicas que responderam. Além disso, duas leituras concorrentes durante uma escrita em curso podem devolver valores diferentes. Linearizabilidade exige consenso — Raft ou Paxos — e é por isso que o etcd existe.

**Quórum relaxado quebra a interseção de quóruns.** Com hinted handoff, as réplicas que confirmaram podem não ser as da lista de preferência, então uma leitura com R réplicas da lista pode não cruzar com a escrita. Nossa medição de 0% de obsolescência em modo forte vale **porque** o modo forte usa `sloppy=false`. Com `sloppy=true` e falha simultânea, a garantia não se sustenta — e é por isso que o parâmetro é explícito em vez de implícito.

**Coordenador fora da lista de preferência faz escrita cega.** Qualquer nó aceita coordenar qualquer chave. Se não é réplica, não conhece o relógio atual e parte do vetor vazio, gerando sibling sob concorrência. Visível no passo 4 da demo de partição: node5 coordena e produz `{"node5":1}`, concorrente com `{"node1":1}`. No Dynamo real o cliente roteia para um nó da lista de preferência.

**Anti-entropia com digest linear.** A comparação transfere um mapa chave→vetor inteiro a cada rodada. Com milhões de chaves isso seria inviável; o Dynamo usa árvore de Merkle para comparação logarítmica.

**A injeção de caos simula perda de mensagem, não falha de kernel.** `/chaos/particao` mantém o processo vivo com seu estado; `docker compose stop` mata o processo, que volta vazio. Os dois cenários têm comportamentos diferentes e ambos foram medidos separadamente.

**Medição em máquina única.** 5 containers e o bench competindo pelos mesmos núcleos. Os números de obsolescência são lógicos (dominância de vetor) e por isso robustos; os de latência medem um cluster co-residente e não se transferem para um cluster em rede real.

**Sem persistência.** Reiniciar um nó zera sua réplica. Conveniente para a demo — torna o handoff visível — mas inaceitável em produção.

---

## 7. Trabalhos futuros

1. **Árvore de Merkle** na anti-entropia, para comparação logarítmica.
2. **Membership dinâmico via gossip**, permitindo entrada e saída de nós sem reiniciar.
3. **Carrinho de compras como tipo de dado**, com merge por união — faria os siblings terem significado semântico, como no paper.
4. **Persistência** com log de escrita.
5. **Validação com Maelstrom ou Jepsen**, para checar as afirmações de consistência com ferramenta externa em vez do nosso verificador mínimo.
6. **Dotted version vectors**, para limitar o crescimento dos metadados.

---

## 8. Como reproduzir

```bash
docker compose up --build
```

```powershell
.\scripts\experimentos.ps1 -Repeticoes 3 -Ops 300 -Chaves 30 -LatenciaMs 120 -JitterMs 40
.\scripts\demo-particao.ps1
.\scripts\demo-queda-de-no.ps1 -NoAlvo node3
.\scripts\demo-anti-entropia.ps1
```

Agregação dos resultados:

```powershell
Import-Csv resultados/resultados.csv | Group-Object modo,r,w | ForEach-Object { $_.Name + ' -> ' + [math]::Round(($_.Group | Measure-Object pct_obsoletas -Average).Average,2) + '%' }
```

Ambiente das medições: Windows 11 Home, Docker Desktop 29.9.2 com backend WSL2, JDK Temurin 21.0.13, imagem `minidynamo:1.0.0` de 309 MB, 5 containers mais o bench na mesma máquina.
