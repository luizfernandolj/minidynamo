# Estudo do tema — esqueleto

> Entregável 1: conceitos, aplicações, limitações, problemas em aberto e estado da arte, com referências a artigos acadêmicos ou documentação técnica oficial.
>
> Este arquivo é um roteiro, não o texto final. Cada seção traz o argumento que deve ser feito e a referência que o sustenta.

## 1. O problema

Por que replicar: disponibilidade, latência geográfica, tolerância a falhas. E o preço: réplicas podem divergir.

**Teorema CAP** (Brewer 2000; prova em Gilbert & Lynch 2002). Cuidado com a leitura simplista: a escolha entre C e A só se impõe *durante* uma partição. Fora dela não há trade-off — citar Brewer, *CAP Twelve Years Later* (2012), e preferir **PACELC** (Abadi 2012), que acrescenta o trade-off latência × consistência no caso normal. Nosso experimento mede exatamente esse eixo "ELC".

## 2. Modelos de consistência

Montar o espectro, do mais forte ao mais fraco:

- **Linearizabilidade** (Herlihy & Wing 1990) — toda operação parece instantânea em um ponto entre início e fim.
- **Sequencial** (Lamport 1979).
- **Causal** (Ahamad et al. 1995; Lloyd et al., COPS, SOSP 2011) — ordena o que é causalmente relacionado, deixa o concorrente livre. É o modelo mais forte compatível com disponibilidade total (Mahajan et al. 2011) — esse resultado é um bom ponto alto do seminário.
- **Eventual** (Vogels 2009) — na ausência de novas escritas, as réplicas convergem.
- **Garantias de sessão** (Terry et al. 1994): read-your-writes, monotonic reads, writes-follow-reads. É o que nosso modo `causal` implementa via contexto.

Referência de síntese: Bailis & Ghodsi, *Eventual Consistency Today* (ACM Queue 2013).

## 3. Mecanismos

- **Relógios lógicos e vetoriais** (Lamport 1978; Mattern 1988; Fidge 1988). Por que timestamp de parede não serve.
- **Version vectors** vs vector clocks: a distinção de Preguiça et al., *Dotted Version Vectors* (2010), e o problema de crescimento do vetor.
- **Quóruns** (Gifford 1979; Thomas 1979). A condição `R+W > N` e por que ela **não** dá linearizabilidade.
- **Sloppy quorum e hinted handoff** (Dynamo, §4.6).
- **Anti-entropia com árvores de Merkle** (Dynamo, §4.7).
- **Read repair** e sua insuficiência sem anti-entropia.
- **CRDTs** (Shapiro et al. 2011) como alternativa: convergência por construção, sem expor conflito.

## 4. Estudo de caso: Dynamo

DeCandia et al., SOSP 2007. Pontos a destacar: zero-hop DHT, consistência ajustável por operação, reconciliação na leitura delegada à aplicação (o exemplo do carrinho de compras), e a decisão de nunca rejeitar uma escrita.

Linhagem: Dynamo → Cassandra, Riak, Voldemort, DynamoDB. Vale registrar que o DynamoDB *serviço* não é o Dynamo *paper* — mudou bastante (ver Elhemali et al., *Amazon DynamoDB: A Scalable, Predictably Performant, and Fully Managed NoSQL Database Service*, USENIX ATC 2022).

## 5. Aplicações

Onde eventual basta (carrinho, contadores, feed, cache, DNS) e onde não basta (saldo bancário, reserva de assento, estoque, locks). Critério útil: o custo de resolver o conflito depois é menor que o custo de indisponibilidade?

## 6. Limitações e problemas em aberto

- Crescimento dos vetores de versão com muitos clientes/nós.
- Remoção em sistemas eventuais: tombstones e quando coletá-los.
- Explosão de siblings sob concorrência alta.
- Testar consistência é difícil — por que o Jepsen importa.
- Consistência em transações entre partições (Spanner, Calvin, Percolator).
- Consistência causal com baixa sobrecarga de metadados (ainda ativo na literatura).

## 7. Estado da arte

HLC (Kulkarni et al. 2014); Spanner e TrueTime; CRDTs em produção (Redis Enterprise, Automerge, Yjs); aplicações local-first; verificação formal de modelos de consistência.

## A preencher

- [ ] Escrever o texto corrido de cada seção
- [ ] Fechar a bibliografia em formato ABNT
- [ ] Decidir o recorte: o enunciado pede profundidade, não cobertura total
- [ ] Validar o recorte com o professor
