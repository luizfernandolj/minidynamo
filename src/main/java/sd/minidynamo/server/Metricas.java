package sd.minidynamo.server;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.time.Duration;

/**
 * Metricas expostas em /metrics no formato Prometheus.
 *
 * Os percentis sao calculados no proprio no (publishPercentileHistogram) para
 * que p95 e p99 saiam direto no scrape, sem precisar de Prometheus de pe para
 * a demo.
 */
public final class Metricas {

    public final PrometheusMeterRegistry registry =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    private final String noId;

    public Metricas(String noId) {
        this.noId = noId;
    }

    public Timer timerLeitura(String modo) {
        return Timer.builder("minidynamo.leitura")
                .tag("no", noId).tag("modo", modo)
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofSeconds(5))
                .register(registry);
    }

    public Timer timerEscrita(String modo) {
        return Timer.builder("minidynamo.escrita")
                .tag("no", noId).tag("modo", modo)
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofSeconds(5))
                .register(registry);
    }

    public Counter quorumFalhou(String operacao) {
        return Counter.builder("minidynamo.quorum.falhou")
                .tag("no", noId).tag("operacao", operacao)
                .register(registry);
    }

    public Counter conflitosDetectados() {
        return Counter.builder("minidynamo.conflitos")
                .tag("no", noId)
                .register(registry);
    }

    public Counter readRepair() {
        return Counter.builder("minidynamo.read.repair")
                .tag("no", noId)
                .register(registry);
    }

    public Counter hintsCriados() {
        return Counter.builder("minidynamo.hints.criados")
                .tag("no", noId)
                .register(registry);
    }

    public Counter hintsEntregues() {
        return Counter.builder("minidynamo.hints.entregues")
                .tag("no", noId)
                .register(registry);
    }

    public Counter sincronizacoesAntiEntropia() {
        return Counter.builder("minidynamo.antientropia.chaves.sincronizadas")
                .tag("no", noId)
                .register(registry);
    }

    public Counter rpcFalhou() {
        return Counter.builder("minidynamo.rpc.falhou")
                .tag("no", noId)
                .register(registry);
    }

    public String scrape() {
        return registry.scrape();
    }
}
