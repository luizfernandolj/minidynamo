package sd.minidynamo.server;

import sd.minidynamo.core.HashRing;
import sd.minidynamo.core.Reconciliador;
import sd.minidynamo.core.Store;
import sd.minidynamo.core.VersionVector;
import sd.minidynamo.core.Versioned;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Qualquer no pode coordenar qualquer chave: nao existe mestre. O no que
 * recebe a requisicao calcula a lista de preferencia, fala com as replicas e
 * decide quando ja tem resposta suficiente.
 *
 * Aqui esta o coracao do trabalho: o quorum e parametro de REQUISICAO, nao de
 * cluster. O mesmo binario, o mesmo dado e a mesma falha produzem resultados
 * diferentes conforme R e W -- e e isso que torna a demonstracao comparativa
 * possivel sem manter tres sistemas.
 */
public final class Coordenador {

    public enum Modo {
        /**
         * R + W > N. Toda leitura cruza com toda escrita em pelo menos uma
         * replica, entao nao se le valor velho. O preco e disponibilidade:
         * sem W replicas vivas a escrita e recusada.
         */
        FORTE,
        /**
         * R = W = 1. Responde enquanto houver uma replica de pe, inclusive dos
         * dois lados de uma particao. Em troca, leitura logo apos escrita pode
         * devolver valor velho, e escritas concorrentes divergem.
         */
        EVENTUAL,
        /**
         * R = W = 1, mas o cliente carrega o contexto (vetor) das operacoes
         * que ja viu. O coordenador se recusa a responder com um estado que
         * seja ANTERIOR a esse contexto: continua consultando replicas ate
         * achar uma que ja tenha visto o que o cliente viu. Da read-your-writes
         * e monotonic reads sem pagar quorum.
         */
        CAUSAL;

        public static Modo de(String s) {
            if (s == null || s.isBlank()) return FORTE;
            return switch (s.toLowerCase()) {
                case "forte", "strong" -> FORTE;
                case "eventual" -> EVENTUAL;
                case "causal" -> CAUSAL;
                default -> throw new IllegalArgumentException(
                        "modo invalido: " + s + " (use forte, eventual ou causal)");
            };
        }

        public int rPadrao(Config cfg) {
            return this == FORTE ? cfg.quorumPadrao() : 1;
        }

        public int wPadrao(Config cfg) {
            return this == FORTE ? cfg.quorumPadrao() : 1;
        }

        public String rotulo() {
            return name().toLowerCase();
        }
    }

    /** Resultado de um RPC a uma replica. Nunca lanca: o erro vem no campo. */
    private record Ack(String no, boolean ok, List<Versioned> versoes, String erro) {

        static Ack sucesso(String no) {
            return new Ack(no, true, List.of(), null);
        }

        static Ack sucesso(String no, List<Versioned> versoes) {
            return new Ack(no, true, versoes, null);
        }

        static Ack falha(String no, String erro) {
            return new Ack(no, false, List.of(), erro);
        }
    }

    private final Config cfg;
    private final Store store;
    private final HashRing ring;
    private final PeerClient peers;
    private final Metricas metricas;
    private final ExecutorService executor;

    public Coordenador(Config cfg, Store store, HashRing ring, PeerClient peers,
                       Metricas metricas, ExecutorService executor) {
        this.cfg = cfg;
        this.store = store;
        this.ring = ring;
        this.peers = peers;
        this.metricas = metricas;
        this.executor = executor;
    }

    // ------------------------------------------------------------------ escrita

    public Dtos.RespostaEscrita escrever(String chave, String valor, VersionVector contexto,
                                         Modo modo, Integer wPedido, boolean remocao) {
        long t0 = System.nanoTime();
        int n = cfg.n();
        int w = wPedido != null ? wPedido : modo.wPadrao(cfg);

        var pref = ring.listaDePreferencia(chave, n);

        // De onde parte o relogio da nova versao:
        //  - se o cliente mandou contexto (leu antes), partimos dele: a escrita
        //    declara "vi exatamente isto e estou sobrescrevendo";
        //  - se nao mandou, partimos do que esta replica conhece. E uma escrita
        //    cega, e e justamente ela que gera siblings sob concorrencia.
        VersionVector base = (contexto != null && !contexto.estaVazio())
                ? contexto
                : store.relogioDe(chave);

        var relogio = base.incrementar(cfg.noId());
        var versao = remocao ? Versioned.remocao(relogio) : Versioned.de(valor, relogio);

        int ok = 0;
        var falharam = new ArrayList<String>();

        // O coordenador grava localmente primeiro quando e uma das replicas.
        // Assim o dado sobrevive mesmo se o quorum nao fechar -- e exatamente
        // por isso que um 503 aqui NAO significa "nada foi escrito".
        boolean souReplica = pref.contains(cfg.noId());
        if (souReplica) {
            store.gravar(chave, versao);
            ok++;
        }

        var cs = new ExecutorCompletionService<Ack>(executor);
        int pendentes = 0;
        for (String no : pref) {
            if (no.equals(cfg.noId())) continue;
            final String destino = no;
            cs.submit(() -> {
                try {
                    peers.gravarRemoto(destino, chave, versao);
                    return Ack.sucesso(destino);
                } catch (Exception e) {
                    return Ack.falha(destino, e.getMessage());
                }
            });
            pendentes++;
        }

        long prazo = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cfg.replicacaoTimeoutMs());
        while (ok < w && pendentes > 0) {
            long restante = prazo - System.nanoTime();
            if (restante <= 0) break;
            try {
                var f = cs.poll(restante, TimeUnit.NANOSECONDS);
                if (f == null) break;
                pendentes--;
                var ack = f.get();
                if (ack.ok()) {
                    ok++;
                } else {
                    falharam.add(ack.no());
                    metricas.rpcFalhou().increment();
                }
            } catch (Exception e) {
                pendentes--;
            }
        }
        // As tarefas que sobraram seguem rodando. Nao as cancelamos de proposito:
        // essa replicacao tardia e literalmente a "consistencia eventual".

        boolean atingiu = ok >= w;
        if (!atingiu) {
            metricas.quorumFalhou("escrita").increment();
        }
        long duracaoMs = (System.nanoTime() - t0) / 1_000_000;
        metricas.timerEscrita(modo.rotulo()).record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);

        return new Dtos.RespostaEscrita(
                chave, relogio.comoMapa(), cfg.noId(), modo.rotulo(),
                w, n, ok, List.copyOf(falharam), atingiu, duracaoMs);
    }

    // ------------------------------------------------------------------ leitura

    public Dtos.RespostaLeitura ler(String chave, VersionVector contexto, Modo modo, Integer rPedido) {
        long t0 = System.nanoTime();
        int n = cfg.n();
        int r = rPedido != null ? rPedido : modo.rPadrao(cfg);

        var pref = ring.listaDePreferencia(chave, n);
        var respostasPorNo = new HashMap<String, List<Versioned>>();
        var falharam = new ArrayList<String>();
        int ok = 0;

        if (pref.contains(cfg.noId())) {
            respostasPorNo.put(cfg.noId(), store.ler(chave));
            ok++;
        }

        var cs = new ExecutorCompletionService<Ack>(executor);
        int pendentes = 0;
        for (String no : pref) {
            if (no.equals(cfg.noId())) continue;
            final String destino = no;
            cs.submit(() -> {
                try {
                    return Ack.sucesso(destino, peers.lerRemoto(destino, chave));
                } catch (Exception e) {
                    return Ack.falha(destino, e.getMessage());
                }
            });
            pendentes++;
        }

        long prazo = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cfg.replicacaoTimeoutMs());
        while (pendentes > 0 && precisaDeMaisRespostas(ok, r, modo, contexto, respostasPorNo)) {
            long restante = prazo - System.nanoTime();
            if (restante <= 0) break;
            try {
                var f = cs.poll(restante, TimeUnit.NANOSECONDS);
                if (f == null) break;
                pendentes--;
                var ack = f.get();
                if (ack.ok()) {
                    respostasPorNo.put(ack.no(), ack.versoes());
                    ok++;
                } else {
                    falharam.add(ack.no());
                    metricas.rpcFalhou().increment();
                }
            } catch (Exception e) {
                pendentes--;
            }
        }

        // Junta tudo o que veio e remove as versoes que alguem ja superou.
        var todas = new ArrayList<Versioned>();
        respostasPorNo.values().forEach(todas::addAll);
        var reconciliadas = Reconciliador.reconciliar(todas);
        var contextoFinal = Reconciliador.contextoDe(reconciliadas);

        boolean causalSatisfeita = contexto == null
                || contexto.estaVazio()
                || contextoFinal.dominaOuIgual(contexto);

        if (reconciliadas.size() > 1) {
            metricas.conflitosDetectados().increment();
        }
        if (ok < r) {
            metricas.quorumFalhou("leitura").increment();
        }

        repararReplicasAtrasadas(chave, respostasPorNo, reconciliadas);

        long duracaoMs = (System.nanoTime() - t0) / 1_000_000;
        metricas.timerLeitura(modo.rotulo()).record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);

        var visiveis = reconciliadas.stream()
                .filter(v -> !v.tombstone())
                .map(Dtos.VersaoDto::de)
                .toList();

        return new Dtos.RespostaLeitura(
                chave, visiveis, contextoFinal.comoMapa(), cfg.noId(), modo.rotulo(),
                r, n, respostasPorNo.size() + falharam.size(), ok, List.copyOf(falharam),
                reconciliadas.size() > 1, causalSatisfeita, duracaoMs);
    }

    /**
     * No modo CAUSAL nao basta juntar R respostas: se nenhuma das replicas
     * consultadas tinha visto o que o cliente ja viu, responder seria voltar no
     * tempo. Entao seguimos consultando as replicas restantes.
     */
    private boolean precisaDeMaisRespostas(int ok, int r, Modo modo, VersionVector contexto,
                                           Map<String, List<Versioned>> respostas) {
        if (ok < r) return true;
        if (modo != Modo.CAUSAL || contexto == null || contexto.estaVazio()) return false;

        var todas = new ArrayList<Versioned>();
        respostas.values().forEach(todas::addAll);
        return !Reconciliador.contextoDe(todas).dominaOuIgual(contexto);
    }

    /**
     * Read repair: a replica que respondeu com um estado incompleto recebe de
     * volta o conjunto reconciliado. E barato (a leitura ja pagou a ida) e faz
     * a convergencia acontecer no caminho quente, sem esperar anti-entropia.
     */
    private void repararReplicasAtrasadas(String chave, Map<String, List<Versioned>> respostas,
                                          List<Versioned> reconciliadas) {
        if (reconciliadas.isEmpty()) return;

        for (var entrada : respostas.entrySet()) {
            String no = entrada.getKey();
            var tinha = entrada.getValue();
            boolean atrasada = reconciliadas.stream().anyMatch(v -> !tinha.contains(v));
            if (!atrasada) continue;

            metricas.readRepair().increment();
            if (no.equals(cfg.noId())) {
                store.gravarTodas(chave, reconciliadas);
                continue;
            }
            // Em background: o cliente nao deve esperar pelo reparo.
            executor.submit(() -> {
                for (Versioned v : reconciliadas) {
                    try {
                        peers.gravarRemoto(no, chave, v);
                    } catch (Exception ignorada) {
                        // Falhou o reparo: a anti-entropia pega depois.
                    }
                }
            });
        }
    }
}
