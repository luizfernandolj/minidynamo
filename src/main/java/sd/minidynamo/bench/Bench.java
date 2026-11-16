package sd.minidynamo.bench;

import sd.minidynamo.core.VersionVector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gerador de carga e verificador de consistencia.
 *
 * O padrao de cada operacao e deliberado: escreve em um no e le em OUTRO,
 * imediatamente. E o caso mais hostil para consistencia eventual e o que
 * produz o numero que interessa -- a fracao de leituras obsoletas.
 *
 * A deteccao de leitura obsoleta NAO compara strings de valor. Compara
 * relogios vetoriais: se o contexto devolvido pela leitura nao domina o
 * contexto que a escrita confirmou, aquela leitura voltou no tempo. Comparar
 * valores daria falso negativo sempre que duas escritas gravassem o mesmo
 * texto.
 */
public final class Bench {

    public static void main(String[] args) throws Exception {
        var cfg = BenchConfig.de(args);
        System.out.println(cfg.descricao());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cliente = new ClienteKv(executor, Duration.ofMillis(cfg.timeoutMs()));

            aguardarCluster(cliente, cfg);

            var est = new Estatisticas();
            var tarefas = new ArrayList<java.util.concurrent.Callable<Void>>();

            // Limita quantas operacoes ficam em voo ao mesmo tempo. Sem isto,
            // as N operacoes partem juntas, enfileiram nos nos e o p95 medido
            // passa a ser o tempo de espera na fila do proprio bench.
            var vagas = new java.util.concurrent.Semaphore(cfg.concorrencia());

            for (int i = 0; i < cfg.operacoes(); i++) {
                final int idx = i;
                tarefas.add(() -> {
                    vagas.acquire();
                    try {
                        executarUmaOperacao(cliente, cfg, est, idx);
                    } finally {
                        vagas.release();
                    }
                    return null;
                });
            }

            long t0 = System.nanoTime();
            var futuros = executor.invokeAll(tarefas);
            for (var f : futuros) {
                try {
                    f.get();
                } catch (Exception ignorada) {
                    // Erros individuais ja foram contabilizados em Estatisticas.
                }
            }
            long duracaoTotalNanos = System.nanoTime() - t0;

            var relatorio = est.consolidar(cfg, duracaoTotalNanos);
            System.out.println(relatorio.comoTexto());
            gravarCsv(cfg, relatorio);
        }
    }

    /**
     * Uma operacao = escrita num no + leitura imediata em outro no.
     * No modo causal, a leitura carrega o contexto devolvido pela escrita: e
     * assim que se exige read-your-writes.
     */
    private static void executarUmaOperacao(ClienteKv cliente, BenchConfig cfg,
                                            Estatisticas est, int idx) {
        var rnd = ThreadLocalRandom.current();
        String chave = "chave-" + rnd.nextInt(cfg.chaves());
        String valor = "v" + idx + "-" + System.nanoTime();

        String noEscrita = cfg.nos().get(rnd.nextInt(cfg.nos().size()));
        var escrita = cliente.escrever(noEscrita, chave, valor, null, cfg.modo(), cfg.w(), cfg.sloppy());

        est.registrarEscrita(escrita.latenciaNanos(), escrita.ok(), escrita.status());
        if (!escrita.ok() || escrita.corpo() == null) {
            return;
        }
        var contextoEscrito = new VersionVector(escrita.corpo().contexto());

        // Le de um no DIFERENTE quando houver mais de um.
        String noLeitura = noEscrita;
        if (cfg.nos().size() > 1) {
            while (noLeitura.equals(noEscrita)) {
                noLeitura = cfg.nos().get(rnd.nextInt(cfg.nos().size()));
            }
        }

        Map<String, Long> contextoEnviado =
                "causal".equals(cfg.modo()) ? escrita.corpo().contexto() : null;

        var leitura = cliente.ler(noLeitura, chave, contextoEnviado, cfg.modo(), cfg.r());
        est.registrarLeitura(leitura.latenciaNanos(), leitura.ok(), leitura.status());

        if (leitura.corpo() == null) {
            return;
        }
        if (leitura.corpo().conflito()) {
            est.conflito();
        }

        // 409: nenhuma replica consultada tinha visto o que o cliente ja viu, e
        // o coordenador preferiu recusar a voltar no tempo. Isso NAO e violacao
        // de causalidade -- e a garantia funcionando. Por isso conta em coluna
        // propria e nao entra na obsolescencia.
        if (leitura.status() == 409) {
            est.recusaCausal();
            return;
        }

        // Obsolescencia so se mede em leitura que o sistema de fato serviu.
        // Um 503 e recusa por falta de quorum: conta-lo como "obsoleta"
        // atribuiria ao modelo de consistencia um erro que foi indisponibilidade,
        // e foi exatamente esse engano que inflou a primeira rodada de medicoes.
        if (leitura.status() != 200 && leitura.status() != 404) {
            return;
        }

        var contextoLido = new VersionVector(leitura.corpo().contexto());
        est.leituraAvaliada();
        if (!contextoLido.dominaOuIgual(contextoEscrito)) {
            est.leituraObsoleta();
        }
    }

    /** Espera os nos responderem /health antes de medir, para nao poluir o p99. */
    private static void aguardarCluster(ClienteKv cliente, BenchConfig cfg) throws InterruptedException {
        System.out.print("aguardando cluster");
        for (int tentativa = 0; tentativa < 60; tentativa++) {
            boolean todosDePe = true;
            for (String no : cfg.nos()) {
                var r = cliente.ler(no, "__aquecimento__", null, "eventual", 1);
                if (r.erro() != null) {
                    todosDePe = false;
                    break;
                }
            }
            if (todosDePe) {
                System.out.println(" ok");
                return;
            }
            System.out.print(".");
            Thread.sleep(500);
        }
        System.out.println(" seguindo mesmo assim (algum no nao respondeu)");
    }

    private static void gravarCsv(BenchConfig cfg, Relatorio rel) throws IOException {
        Path destino = Path.of(cfg.csv());
        if (destino.getParent() != null) {
            Files.createDirectories(destino.getParent());
        }
        boolean novo = !Files.exists(destino);

        var sb = new StringBuilder();
        if (novo) {
            sb.append("rotulo,modo,r,w,sloppy,operacoes,chaves,concorrencia,")
              .append("escritas_ok,escritas_erro,leituras_ok,leituras_erro,")
              .append("leituras_avaliadas,leituras_obsoletas,pct_obsoletas,")
              .append("conflitos,recusas_causais,")
              .append("escrita_p50_ms,escrita_p95_ms,escrita_p99_ms,")
              .append("leitura_p50_ms,leitura_p95_ms,leitura_p99_ms,vazao_ops_s\n");
        }
        sb.append(rel.comoCsv());
        Files.writeString(destino, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("resultado anexado em " + destino.toAbsolutePath());
    }

    // ------------------------------------------------------------- configuracao

    record BenchConfig(List<String> nos, String modo, Integer r, Integer w, Boolean sloppy,
                       int operacoes, int chaves, int concorrencia, long timeoutMs,
                       String rotulo, String csv) {

        static BenchConfig de(String[] args) {
            var mapa = new java.util.HashMap<String, String>();
            for (String a : args) {
                int i = a.indexOf('=');
                if (a.startsWith("--") && i > 0) {
                    mapa.put(a.substring(2, i), a.substring(i + 1));
                }
            }
            String nosRaw = valor(mapa, "nos", "NODES",
                    "http://localhost:8081,http://localhost:8082,http://localhost:8083," +
                    "http://localhost:8084,http://localhost:8085");

            return new BenchConfig(
                    Arrays.stream(nosRaw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                    valor(mapa, "modo", "MODO", "forte"),
                    inteiroOuNulo(valor(mapa, "r", "R", null)),
                    inteiroOuNulo(valor(mapa, "w", "W", null)),
                    booleanoOuNulo(valor(mapa, "sloppy", "SLOPPY", null)),
                    Integer.parseInt(valor(mapa, "ops", "OPS", "300")),
                    Integer.parseInt(valor(mapa, "chaves", "CHAVES", "50")),
                    // Sem limite de concorrencia a medicao perde sentido: as
                    // operacoes passam a medir fila do bench, nao latencia do
                    // sistema. 16 em voo mantem os nos ocupados sem saturar.
                    Integer.parseInt(valor(mapa, "concorrencia", "CONCORRENCIA", "16")),
                    Long.parseLong(valor(mapa, "timeoutMs", "TIMEOUT_MS", "3000")),
                    valor(mapa, "rotulo", "ROTULO", "sem-rotulo"),
                    valor(mapa, "csv", "CSV", "resultados/resultados.csv"));
        }

        private static String valor(Map<String, String> args, String chaveArg, String chaveEnv, String padrao) {
            if (args.containsKey(chaveArg)) return args.get(chaveArg);
            String env = System.getenv(chaveEnv);
            return (env == null || env.isBlank()) ? padrao : env.trim();
        }

        private static Integer inteiroOuNulo(String s) {
            return (s == null || s.isBlank()) ? null : Integer.valueOf(s);
        }

        private static Boolean booleanoOuNulo(String s) {
            return (s == null || s.isBlank()) ? null : Boolean.valueOf(s);
        }

        String descricao() {
            return String.format(
                    "=== bench [%s] modo=%s R=%s W=%s sloppy=%s ops=%d chaves=%d concorrencia=%d nos=%d ===",
                    rotulo, modo, r == null ? "padrao" : r, w == null ? "padrao" : w,
                    sloppy == null ? "padrao" : sloppy,
                    operacoes, chaves, concorrencia, nos.size());
        }
    }

    // -------------------------------------------------------------- estatisticas

    static final class Estatisticas {
        private final List<Long> latenciaEscrita = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<Long> latenciaLeitura = java.util.Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger escritasOk = new AtomicInteger();
        private final AtomicInteger escritasErro = new AtomicInteger();
        private final AtomicInteger leiturasOk = new AtomicInteger();
        private final AtomicInteger leiturasErro = new AtomicInteger();
        private final AtomicLong obsoletas = new AtomicLong();
        private final AtomicLong avaliadas = new AtomicLong();
        private final AtomicLong conflitos = new AtomicLong();
        private final AtomicLong recusasCausais = new AtomicLong();

        void registrarEscrita(long nanos, boolean ok, int status) {
            latenciaEscrita.add(nanos);
            if (ok) escritasOk.incrementAndGet();
            else escritasErro.incrementAndGet();
        }

        void registrarLeitura(long nanos, boolean ok, int status) {
            latenciaLeitura.add(nanos);
            // 404 e resposta legitima: a chave pode nao existir ainda.
            if (ok || status == 404) leiturasOk.incrementAndGet();
            else leiturasErro.incrementAndGet();
        }

        void leituraObsoleta() {
            obsoletas.incrementAndGet();
        }

        /** Leitura que o sistema realmente serviu, logo passivel de estar obsoleta. */
        void leituraAvaliada() {
            avaliadas.incrementAndGet();
        }

        void conflito() {
            conflitos.incrementAndGet();
        }

        void recusaCausal() {
            recusasCausais.incrementAndGet();
        }

        Relatorio consolidar(BenchConfig cfg, long duracaoTotalNanos) {
            double segundos = duracaoTotalNanos / 1e9;
            // O denominador e o numero de leituras AVALIADAS, nao o de leituras
            // bem-sucedidas: so entram as que o sistema serviu de fato.
            long base = avaliadas.get();
            double pct = base == 0 ? 0.0 : 100.0 * obsoletas.get() / base;

            return new Relatorio(cfg,
                    escritasOk.get(), escritasErro.get(), leiturasOk.get(), leiturasErro.get(),
                    obsoletas.get(), base, pct, conflitos.get(), recusasCausais.get(),
                    percentil(latenciaEscrita, 50), percentil(latenciaEscrita, 95), percentil(latenciaEscrita, 99),
                    percentil(latenciaLeitura, 50), percentil(latenciaLeitura, 95), percentil(latenciaLeitura, 99),
                    segundos == 0 ? 0 : cfg.operacoes() / segundos);
        }

        private static double percentil(List<Long> amostras, int p) {
            synchronized (amostras) {
                if (amostras.isEmpty()) return 0;
                var ordenado = amostras.stream().sorted().toList();
                int idx = (int) Math.ceil(p / 100.0 * ordenado.size()) - 1;
                return ordenado.get(Math.max(0, Math.min(idx, ordenado.size() - 1))) / 1e6;
            }
        }
    }

    record Relatorio(BenchConfig cfg, int escritasOk, int escritasErro, int leiturasOk, int leiturasErro,
                     long obsoletas, long avaliadas, double pctObsoletas, long conflitos, long recusasCausais,
                     double escritaP50, double escritaP95, double escritaP99,
                     double leituraP50, double leituraP95, double leituraP99, double vazao) {

        String comoTexto() {
            return String.format(Locale.US,
                    """

                    --- resultado [%s] modo=%s R=%s W=%s concorrencia=%d ---
                      escritas             ok=%d erro=%d
                      leituras             ok=%d erro=%d
                      leituras avaliadas   %d
                      LEITURAS OBSOLETAS   %d  (%.2f%% das avaliadas)
                      conflitos (siblings) %d
                      recusas causais 409  %d
                      escrita  p50=%.1f ms  p95=%.1f ms  p99=%.1f ms
                      leitura  p50=%.1f ms  p95=%.1f ms  p99=%.1f ms
                      vazao                %.0f ops/s
                    """,
                    cfg.rotulo(), cfg.modo(),
                    cfg.r() == null ? "padrao" : cfg.r(), cfg.w() == null ? "padrao" : cfg.w(),
                    cfg.concorrencia(),
                    escritasOk, escritasErro, leiturasOk, leiturasErro,
                    avaliadas, obsoletas, pctObsoletas, conflitos, recusasCausais,
                    escritaP50, escritaP95, escritaP99,
                    leituraP50, leituraP95, leituraP99, vazao);
        }

        String comoCsv() {
            return String.format(Locale.US,
                    "%s,%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.1f%n",
                    cfg.rotulo(), cfg.modo(),
                    cfg.r() == null ? "" : cfg.r(), cfg.w() == null ? "" : cfg.w(),
                    cfg.sloppy() == null ? "" : cfg.sloppy(),
                    cfg.operacoes(), cfg.chaves(), cfg.concorrencia(),
                    escritasOk, escritasErro, leiturasOk, leiturasErro,
                    avaliadas, obsoletas, pctObsoletas, conflitos, recusasCausais,
                    escritaP50, escritaP95, escritaP99,
                    leituraP50, leituraP95, leituraP99, vazao);
        }
    }
}
