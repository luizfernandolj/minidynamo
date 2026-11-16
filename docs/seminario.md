# Roteiro do seminário — 25 minutos

**Replicação e modelos de consistência: forte, eventual, causal; quóruns no estilo Dynamo/Cassandra**

Luiz Fernando Luth Junior · Dyogo Romagna · Gustavo de Almeida Neves

| Bloco | Tempo | Quem |
|---|---|---|
| 1. O problema e o CAP | 4 min | Gustavo |
| 2. Os modelos de consistência | 5 min | Gustavo |
| 3. Mecanismos do Dynamo | 5 min | Luiz Fernando |
| 4. **Demo ao vivo** | 7 min | Dyogo |
| 5. Resultados medidos | 3 min | Luiz Fernando |
| 6. Limitações e fechamento | 1 min | todos |

---

## Antes de começar (checklist)

```bash
docker compose up -d --build
```

- [ ] `docker compose ps` → 5 nós `healthy`
- [ ] Terminal com fonte grande, janela maximizada
- [ ] `curl localhost:8081/health` responde
- [ ] **Plano B:** se o Docker falhar, `.\scripts\local-5nos.ps1` sobe os 5 nós como processos locais nas mesmas portas; todos os scripts funcionam igual
- [ ] **Plano C:** screenshots das saídas em `docs/` — nunca depure ao vivo

---

## Bloco 1 — O problema (4 min)

**Abertura, sem slide:**

> Vocês adicionam um item ao carrinho da Amazon. Esse clique **nunca** pode falhar — cada falha é receita perdida. Mas o carrinho está replicado em vários datacenters. Se a rede entre eles cair, e vocês adicionarem um item de um lado enquanto outra sessão adiciona de outro, quem está certo?

**Slide 1 — Por que replicar:** tolerância a falha, latência geográfica, distribuição de carga. O preço: réplicas discordam.

**Slide 2 — CAP:** Consistência, Disponibilidade, tolerância a Partição.

Dizer explicitamente que a leitura "escolha 2 de 3" é ruim:
- Partição não é escolha — redes particionam.
- A escolha C vs A só existe **durante** a partição.

**Slide 3 — PACELC** (Abadi 2012): se há Partição, A ou C; **senão**, Latência ou Consistência.

> O segundo ramo é o que domina a operação real. Mesmo com a rede perfeita, esperar mais réplicas custa tempo. Nós medimos isso: 3,2× de latência.

---

## Bloco 2 — Os modelos (5 min)

**Slide 4 — O espectro:**

```
linearizável  >  sequencial  >  causal  >  garantias de sessão  >  eventual
    (caro)                                                        (barato)
```

**Slide 5 — Os três que implementamos:**

| Modelo | Promessa | Preço |
|---|---|---|
| **Forte** (`R+W>N`) | não existe leitura obsoleta | sem W réplicas, recusa a escrita |
| **Eventual** (`R=W=1`) | responde sempre, converge depois | leitura pode voltar no tempo |
| **Causal** (contexto) | read-your-writes, monotonic reads | recusa (409) se nenhuma réplica viu seu contexto |

**Slide 6 — Dois resultados teóricos que valem citar:**

- Causal é o modelo **mais forte compatível com disponibilidade total** (Mahajan et al. 2011).
- "Eventual" não tem prazo. A pergunta útil não é *se* converge, é **quão obsoleto fica enquanto não converge** (Bailis & Ghodsi 2013) — e isso se mede.

---

## Bloco 3 — Mecanismos (5 min)

**Slide 7 — Relógios vetoriais.** Por que timestamp de parede não serve: relógios divergem entre máquinas.

```
A = {node1:2, node2:1}      B = {node1:1, node2:3}
A tem node1 maior, B tem node2 maior  →  CONCORRENTES
```

> Esse caso "incomparável" é o interessante: são duas escritas que não se viram. O Dynamo não escolhe entre elas — devolve as duas.

**Slide 8 — Hashing consistente com vnodes.** Cada nó aparece 128 vezes no anel. Sair um nó realoca O(K/N) chaves, não todas. Verificado em teste: remover 1 de 5 nós move menos de 30% das chaves.

**Slide 9 — Quórum.** `R + W > N` força interseção.

```
N=3, R=2, W=2  →  2+2 > 3  →  todo conjunto de leitura cruza todo conjunto de escrita
```

**Dizer o que isso NÃO dá:** linearizabilidade. Escritas não são atômicas entre réplicas. Quórum ≠ consenso.

**Slide 10 — Hinted handoff.** Réplica fora → substituto guarda a escrita com a dica do dono → entrega quando ele volta.

> E aqui está a parte que a literatura de divulgação costuma omitir: **isso quebra a interseção de quóruns**. Se a escrita foi confirmada por um nó fora da lista de preferência, a leitura pode não cruzar com ela. Por isso no nosso sistema é um parâmetro explícito, e o modo forte o mantém desligado.

---

## Bloco 4 — DEMO AO VIVO (7 min)

> **Regra de ouro:** rode os scripts, não digite comandos. Se algo falhar, siga para o próximo — não depure no palco.

### 4.1 O cluster (30 s)

```bash
docker compose ps
```

> Cinco nós idênticos. O que os diferencia é só a variável NODE_ID. Qualquer um coordena qualquer chave — não existe mestre.

### 4.2 Partição de rede (3 min)

```powershell
.\scripts\demo-particao.ps1
```

Narrar nos pontos-chave:

- **Passo 0:** a chave mora em 3 das 5 réplicas. Escolhemos uma cuja lista inclui os nós que vamos cortar.
- **Passo 3:** com `W=1`, **os dois lados aceitam escrever**. Nenhum sabe do outro.
- **Passo 4** — *pausar aqui*: mostrar os vetores divergentes.
  > `{"node1":2}` e `{"node5":1}`. Nenhum viu o outro. O dado divergiu, e o sistema continuou disponível. Isto é o A do CAP.
- **Passo 5:** com `W=3`, o lado minoritário é **recusado**. Isto é o C.
  > Mesmo sistema, mesma chave, mesma falha. Só mudou o parâmetro da requisição.
- **Passo 7** — *o momento alto*: siblings.
  > As duas escritas sobreviveram. O sistema **não escolheu por timestamp**. Num carrinho, o merge seria a união dos itens. Last-writer-wins teria feito um item sumir do carrinho de um cliente — em silêncio.

### 4.3 Queda de nó e hinted handoff (2,5 min)

```powershell
.\scripts\demo-queda-de-no.ps1 -NoAlvo node3
```

- **Passo 3:** com node3 **morto**, `W=3` ainda fecha. O terceiro ack veio de node5, que guardou a escrita em nome de node3.
- **Passo 4:** a mesma escrita com `sloppy=false` → **503**.
  > Esta é a comparação mais limpa do trabalho: mesma chave, mesma falha, duas políticas. Uma mantém disponibilidade e abre mão da interseção de quóruns; a outra preserva a garantia e recusa.
- **Passo 5:** tempo de recuperação **8,19 s**.
- **Passo 6:** node3 recuperou o dado **sem ninguém ter lido a chave**.

### 4.4 Anti-entropia (1 min, se o tempo permitir)

```powershell
.\scripts\demo-anti-entropia.ps1
```

> Read repair só conserta o que é lido. Uma chave que ninguém lê ficaria divergente para sempre. Aqui isolamos uma réplica, escrevemos com `sloppy=false` para garantir **zero hints**, curamos a rede e **nunca lemos a chave**. Convergiu em 5,16 s, sozinha, por troca periódica de digest.

---

## Bloco 5 — Resultados (3 min)

**Slide 11 — A tabela principal.** N=3, 300 ops, 3 repetições, janela de 120 ms ± 40 ms:

| Config | Obsoletas | Escrita p50 | Leitura p50 | Vazão |
|---|---|---|---|---|
| `R=1 W=1` eventual | **43,22%** | 47,7 ms | **2,9 ms** | **108/s** |
| `R=1 W=1` causal | **0,00%** | 45,7 ms | 132,9 ms | 87/s |
| `R=2 W=2` forte | **0,00%** | 138,3 ms | 138,1 ms | 55/s |
| `R=3 W=3` forte | **0,00%** | 154,1 ms | 152,7 ms | 51/s |

Três pontos, nesta ordem:

1. **`R+W>N` funciona.** Zero obsolescência, todas as repetições.
2. **O custo é latência, sem partição nenhuma.** 3,2× na escrita. É o ramo ELC do PACELC.
3. **O modo causal é o achado.** Zero obsolescência com escrita tão barata quanto a eventual — paga só na leitura. Para carga de escrita intensa, domina `R=2 W=2`.

**Slide 12 — Uma lição de método** (vale ponto, e é honesto):

> Rodando no cluster ocioso, o modo eventual deu **1,33%** de obsolescência. Parecia ótimo. Mas não era consistência — era que a replicação terminava dentro dos 48 ms da própria escrita, e a janela fechava antes da leitura. **Medir consistência eventual sem controlar a janela mede a ociosidade da máquina, não o modelo.** Com a janela em 120 ms: 43,22%, desvio padrão 1,34.

---

## Bloco 6 — Limitações e fechamento (1 min)

**Slide 13 — O que não entregamos:**

- `R+W>N` **não é linearizabilidade** — um 503 não desfaz o que já gravou. Quem precisa disso usa consenso, não quórum. É por isso que o etcd existe.
- Quórum relaxado **quebra a interseção** — nossa medição de 0% vale porque o modo forte usa `sloppy=false`.
- Anti-entropia com digest linear; o Dynamo usa Merkle.
- Medição em máquina única: números de obsolescência são lógicos e robustos, os de latência são de um cluster co-residente.

**Fechamento:**

> Não existe o melhor modelo de consistência — existe o adequado ao dado. O mérito do Dynamo é tornar essa escolha explícita e por operação. O que aprendemos construindo é o custo escondido de cada mecanismo: hinted handoff parece grátis até perceber que ele quebra a propriedade que justifica o quórum; read repair parece suficiente até perceber que só conserta o que é lido; e consistência eventual parece inofensiva até medi-la.

---

## Perguntas prováveis

**"Por que não usaram Raft/Paxos?"**
> Porque o tema é quórum, e a diferença entre os dois é justamente o ponto. Quórum dá consistência de quórum, consenso dá linearizabilidade. Mostramos onde o quórum para.

**"Isso é linearizável com R+W>N?"**
> Não, e é importante. Faltam atomicidade entre réplicas e tratamento de leituras concorrentes durante escrita em curso. É consistência de quórum.

**"Por que não Cassandra direto?"**
> Temos um compose de Cassandra de 3 nós para comparar (`docker-compose.cassandra.yml`). A diferença conceitual: Cassandra resolve conflito por last-writer-wins e descarta a escrita concorrente em silêncio; nós expomos siblings, como Riak e o Dynamo original.

**"O que acontece se dois clientes escreverem ao mesmo tempo sem ler antes?"**
> Escrita cega: cada coordenador parte do vetor que conhece e gera versões concorrentes. Vira sibling. É o comportamento correto — o alternativo seria perder uma das escritas.

**"Quanto do código é de vocês?"**
> Todo o núcleo: anel, relógios vetoriais, coordenador, handoff, anti-entropia e o verificador de consistência. Dependências só para HTTP (Javalin), JSON (Jackson) e métricas (Micrometer).
