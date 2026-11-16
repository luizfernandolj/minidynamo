package sd.minidynamo.server;

import sd.minidynamo.core.HashRing;
import sd.minidynamo.core.Store;
import sd.minidynamo.core.VersionVector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Convergencia em segundo plano entre replicas, independente de leitura.
 *
 * Sem isto, a unica forca de convergencia seria o read repair -- e duas
 * replicas de uma chave que ninguem le poderiam ficar divergentes para sempre.
 * Foi essa a lacuna apontada na primeira versao do projeto.
 *
 * Implementacao: troca de DIGEST. A cada rodada o no sorteia um par, pede o
 * resumo (chave -> vetor) dele e compara com o seu. So transfere valor onde os
 * vetores diferem. O Dynamo usa arvore de Merkle para que a comparacao seja
 * logaritmica no numero de chaves; aqui o digest e linear, o que e aceitavel na
 * escala deste trabalho e esta registrado como limitacao no relatorio.
 */
public final class AntiEntropia implements AutoCloseable {

    private final Config cfg;
    private final Store store;
    private final HashRing ring;
    private final PeerClient peers;
    private final Metricas metricas;
    private final ScheduledExecutorService agenda;

    public AntiEntropia(Config cfg, Store store, HashRing ring, PeerClient peers, Metricas metricas) {
        this.cfg = cfg;
        this.store = store;
        this.ring = ring;
        this.peers = peers;
        this.metricas = metricas;
        this.agenda = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "anti-entropia");
            t.setDaemon(true);
            return t;
        });
    }

    public void iniciar() {
        if (!cfg.antiEntropiaLigada()) return;
        agenda.scheduleWithFixedDelay(
                this::rodadaComParSorteado,
                cfg.antiEntropiaMs(),
                cfg.antiEntropiaMs(),
                TimeUnit.MILLISECONDS);
    }

    private void rodadaComParSorteado() {
        var outros = new ArrayList<>(cfg.idsDosNos());
        outros.remove(cfg.noId());
        if (outros.isEmpty()) return;

        String par = outros.get(ThreadLocalRandom.current().nextInt(outros.size()));
        try {
            sincronizarCom(par);
        } catch (Exception peerIndisponivel) {
            // Normal durante falha. A proxima rodada sorteia outro par.
        }
    }

    /**
     * Uma rodada de reconciliacao com um par. Visivel para teste e para o
     * endpoint /debug/antientropia, que forca uma rodada durante a demo.
     *
     * @return quantas chaves precisaram de transferencia
     */
    public int sincronizarCom(String par) throws Exception {
        var digestDoPar = peers.digestRemoto(par);

        var chaves = new HashSet<String>();
        chaves.addAll(store.dump().keySet());
        chaves.addAll(digestDoPar.relogios().keySet());

        int sincronizadas = 0;
        for (String chave : chaves) {
            // So troca chaves que os dois deveriam guardar. Sem este filtro a
            // anti-entropia espalharia cada chave para o cluster inteiro,
            // desfazendo o particionamento do anel.
            var pref = ring.listaDePreferencia(chave, cfg.n());
            if (!pref.contains(cfg.noId()) || !pref.contains(par)) continue;

            var meu = store.relogioDe(chave);
            var dele = new VersionVector(digestDoPar.relogios().getOrDefault(chave, java.util.Map.of()));

            if (meu.comparar(dele) == VersionVector.Ordem.IGUAL) continue;

            // Puxa o que falta aqui.
            if (!meu.dominaOuIgual(dele)) {
                store.gravarTodas(chave, peers.lerRemoto(par, chave));
            }
            // Empurra o que falta la. Reconsulta o relogio local porque o pull
            // acima pode te-lo avancado.
            if (!dele.dominaOuIgual(store.relogioDe(chave))) {
                for (var versao : store.ler(chave)) {
                    peers.gravarRemoto(par, chave, versao);
                }
            }
            metricas.sincronizacoesAntiEntropia().increment();
            sincronizadas++;
        }
        return sincronizadas;
    }

    @Override
    public void close() {
        agenda.shutdownNow();
    }
}
