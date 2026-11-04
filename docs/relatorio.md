# Relatório técnico da solução — esqueleto

> Entregável 4: arquitetura, decisões de projeto, experimentos realizados, métricas e limitações.

## 1. Arquitetura

Reaproveitar o diagrama e a tabela de componentes do [README](../README.md#arquitetura). Acrescentar aqui o que o README não tem: o fluxo de uma escrita e de uma leitura passo a passo, com os números de réplica.

## 2. Decisões de projeto

Cada decisão com a alternativa que foi descartada e o motivo:

| Decisão | Alternativa descartada | Motivo |
|---|---|---|
| Java 21 + Javalin | Spring Boot | startup de 2–4 s distorceria a métrica de tempo de recuperação |
| Virtual threads no fan-out | pool de threads dimensionado | espera por N réplicas fica trivial e legível |
| Siblings expostos ao cliente | last-writer-wins | LWW descarta escrita concorrente em silêncio |
| Injeção de caos no processo | Toxiproxy / netem | portabilidade: equipe no Windows, sem `NET_ADMIN` |
| Armazenamento em memória | disco | o tema é consistência, não durabilidade; e o nó sobe em ~300 ms |
| Consistência por requisição | três sistemas separados | permite comparação com um único binário |

## 3. Experimentos

Para cada um: hipótese, procedimento, resultado, interpretação.

### 3.1 Obsolescência por configuração de quórum

- **Hipótese:** `R+W > N` elimina leitura obsoleta; `R=W=1` não.
- **Procedimento:** `scripts/experimentos.ps1`, N=3, escrita num nó e leitura imediata em outro, detecção por dominância de relógio vetorial.
- **Resultado:** *(preencher com a média das repetições)*
- Primeira rodada exploratória: R2W2 → 0,00%; R1W1 → 13,25%; causal → 0,00%.

### 3.2 Latência × consistência

- **Hipótese:** p95 cresce com R e W.
- **Atenção:** a primeira rodada deu `R3W3` mais rápido que `R2W2`, o que é artefato de aquecimento de JIT. Usar `experimentos.ps1`, que descarta a rodada de aquecimento e alterna a ordem das configurações. Reportar média e desvio entre repetições, não um valor único.
- **Resultado:** *(preencher)*

### 3.3 Partição de rede

- **Procedimento:** `scripts/demo-particao.ps1`.
- **Resultado:** com `W=1` ambos os lados aceitam escrita e divergem; com `W=3` o lado minoritário recebe 503; após a cura, read repair converge e as escritas concorrentes sobrevivem como siblings.
- **Interpretação:** demonstração concreta de AP vs CP na mesma base de código.

### 3.4 Queda de nó e tempo de recuperação

- **Procedimento:** `scripts/demo-queda-de-no.ps1`.
- **Métrica:** segundos até `W=3` voltar a fechar.
- **Resultado:** *(preencher — depende de o hinted handoff estar implementado)*

### 3.5 Distribuição de carga do anel

- **Procedimento:** `GET /debug/anel` com 10.000 chaves amostradas.
- **Métrica:** desvio em relação ao ideal de 1/N. Justifica usar 128 vnodes por nó.
- **Resultado:** *(preencher)*

## 4. Métricas coletadas

Da saída do bench e de `/metrics`: leituras obsoletas (%), conflitos/siblings, recusas causais (409), p50/p95/p99 de leitura e escrita, vazão, tempo de recuperação, read repairs disparados, falhas de RPC.

## 5. Limitações

Copiar e desenvolver a seção de limitações do [README](../README.md#limitações-que-vão-no-relatório). As quatro principais: quórum não é linearizabilidade; coordenador fora da lista de preferência faz escrita cega; caos simula perda de mensagem e não queda de kernel; medição em máquina única com nós co-residentes.

## 6. Trabalhos futuros

Hinted handoff, anti-entropia com Merkle, membership dinâmico, merge de carrinho, persistência. Ver a lista priorizada no README.

## A preencher

- [ ] Rodar `experimentos.ps1` com pelo menos 3 repetições e preencher os resultados
- [ ] Gerar os gráficos a partir de `resultados/resultados.csv`
- [ ] Implementar hinted handoff antes de medir 3.4
- [ ] Comparação com o cluster Cassandra
