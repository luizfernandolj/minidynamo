package sd.minidynamo.server;

import sd.minidynamo.core.HintStore;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Laco que tenta entregar, de tempos em tempos, as escritas guardadas em nome
 * de replicas que estavam fora do ar.
 *
 * A entrega e o que fecha o ciclo do hinted handoff: sem ela o substituto
 * seguraria o dado para sempre e o no que voltou continuaria sem a escrita que
 * perdeu. E tambem o que torna o tempo de recuperacao mensuravel -- e o
 * intervalo entre o no voltar e os hints dele terem sido todos entregues.
 */
public final class HintedHandoff implements AutoCloseable {

    private final Config cfg;
    private final HintStore hints;
    private final PeerClient peers;
    private final Metricas metricas;
    private final ScheduledExecutorService agenda;

    public HintedHandoff(Config cfg, HintStore hints, PeerClient peers, Metricas metricas) {
        this.cfg = cfg;
        this.hints = hints;
        this.peers = peers;
        this.metricas = metricas;
        this.agenda = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "entrega-de-hints");
            t.setDaemon(true);
            return t;
        });
    }

    public void iniciar() {
        agenda.scheduleWithFixedDelay(
                this::tentarEntregarTudo,
                cfg.entregaDeHintsMs(),
                cfg.entregaDeHintsMs(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Tenta entregar todos os hints pendentes. Visivel para teste e para o
     * endpoint /debug/hints/entregar, que forca a entrega durante a demo sem
     * esperar o proximo tique.
     */
    public int tentarEntregarTudo() {
        int entregues = 0;
        for (String destino : hints.destinosPendentes()) {
            for (HintStore.Hint hint : hints.pendentesPara(destino)) {
                try {
                    peers.gravarRemoto(destino, hint.chave(), hint.versao());
                    hints.marcarEntregue(hint);
                    metricas.hintsEntregues().increment();
                    entregues++;
                } catch (Exception aindaFora) {
                    // O destino continua inalcancavel. Para de insistir neste
                    // destino agora e tenta de novo no proximo tique: seguir
                    // tentando as outras chaves do mesmo no so gastaria timeout.
                    break;
                }
            }
        }
        return entregues;
    }

    @Override
    public void close() {
        agenda.shutdownNow();
    }
}
