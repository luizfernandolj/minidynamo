package sd.minidynamo.server;

import com.fasterxml.jackson.core.type.TypeReference;
import io.javalin.Javalin;
import io.javalin.http.Context;
import sd.minidynamo.core.HashRing;
import sd.minidynamo.core.Store;
import sd.minidynamo.core.VersionVector;
import sd.minidynamo.core.Versioned;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Ponto de entrada de um no. Cinco containers rodam este mesmo main; o que os
 * diferencia e so NODE_ID.
 */
public final class NodeMain {

    public static void main(String[] args) {
        var cfg = Config.doAmbiente();
        var store = new Store();
        var ring = new HashRing(cfg.idsDosNos(), cfg.vnodes());
        var chaos = new Chaos();
        var metricas = new Metricas(cfg.noId());

        // Uma thread virtual por RPC. O coordenador dispara N chamadas em
        // paralelo e espera so as W/R primeiras: com threads de plataforma isso
        // exigiria pool dimensionado a mao; com virtual threads o custo de uma
        // thread parada esperando resposta e irrisorio.
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        var peers = new PeerClient(cfg, chaos, executor);
        var coordenador = new Coordenador(cfg, store, ring, peers, metricas, executor);

        var app = Javalin.create(c -> {
            c.showJavalinBanner = false;
        });

        registrarRotasCliente(app, cfg, coordenador);
        registrarRotasInternas(app, cfg, store);
        registrarRotasCaos(app, chaos);
        registrarRotasDiagnostico(app, cfg, store, ring, metricas, chaos);

        app.exception(IllegalArgumentException.class, (e, ctx) ->
                responder(ctx, 400, new Dtos.Erro("requisicao invalida", e.getMessage())));
        app.exception(Exception.class, (e, ctx) ->
                responder(ctx, 500, new Dtos.Erro("erro interno", String.valueOf(e.getMessage()))));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            executor.close();
        }));

        app.start("0.0.0.0", cfg.porta());

        System.out.printf(
                "[%s] MiniDynamo no ar em :%d | N=%d R=%d W=%d | vnodes=%d | peers=%s%n",
                cfg.noId(), cfg.porta(), cfg.n(), cfg.r(), cfg.w(), cfg.vnodes(),
                cfg.idsDosNos());
    }

    // ------------------------------------------------------------ API do cliente

    private static void registrarRotasCliente(Javalin app, Config cfg, Coordenador coord) {

        app.put("/kv/{chave}", ctx -> {
            String chave = ctx.pathParam("chave");
            String corpo = ctx.body();
            if (corpo == null || corpo.isBlank()) {
                throw new IllegalArgumentException("corpo vazio; esperado {\"valor\":\"...\"}");
            }
            var pedido = Json.ler(corpo, Dtos.PedidoEscrita.class);
            if (pedido.valor() == null) {
                throw new IllegalArgumentException("campo 'valor' obrigatorio");
            }
            // O contexto pode vir no corpo ou no header; o header e mais pratico
            // para o cliente que encadeia operacoes.
            var contexto = pedido.contexto() != null
                    ? new VersionVector(pedido.contexto())
                    : contextoDoHeader(ctx);

            var modo = Coordenador.Modo.de(ctx.queryParam("modo"));
            var resp = coord.escrever(chave, pedido.valor(), contexto, modo, inteiro(ctx, "w"), false);
            responder(ctx, resp.quorumAtingido() ? 200 : 503, resp);
        });

        app.get("/kv/{chave}", ctx -> {
            String chave = ctx.pathParam("chave");
            var modo = Coordenador.Modo.de(ctx.queryParam("modo"));
            var resp = coord.ler(chave, contextoDoHeader(ctx), modo, inteiro(ctx, "r"));

            int status;
            if (resp.replicasOk() < resp.r()) {
                status = 503;                 // nao houve quorum de leitura
            } else if (!resp.causalSatisfeita()) {
                status = 409;                 // nenhuma replica viu o que o cliente ja viu
            } else if (resp.valores().isEmpty()) {
                status = 404;
            } else {
                status = 200;
            }
            responder(ctx, status, resp);
        });

        app.delete("/kv/{chave}", ctx -> {
            String chave = ctx.pathParam("chave");
            var modo = Coordenador.Modo.de(ctx.queryParam("modo"));
            var resp = coord.escrever(chave, null, contextoDoHeader(ctx), modo, inteiro(ctx, "w"), true);
            responder(ctx, resp.quorumAtingido() ? 200 : 503, resp);
        });
    }

    // ------------------------------------------------------------- API interna

    private static void registrarRotasInternas(Javalin app, Config cfg, Store store) {

        // Escrita de replica: aplica local e nao coordena nada. Sem isso
        // teriamos recursao infinita entre coordenadores.
        app.put("/internal/kv/{chave}", ctx -> {
            String chave = ctx.pathParam("chave");
            var pedido = Json.ler(ctx.body(), Dtos.PedidoReplicacao.class);
            store.gravar(chave, pedido.versao().paraDominio());
            responder(ctx, 200, Map.of("no", cfg.noId(), "ok", true));
        });

        app.get("/internal/kv/{chave}", ctx -> {
            String chave = ctx.pathParam("chave");
            List<Versioned> versoes = store.ler(chave);
            responder(ctx, 200, new Dtos.RespostaInterna(
                    cfg.noId(), versoes.stream().map(Dtos.VersaoDto::de).toList()));
        });
    }

    // --------------------------------------------------------------- API de caos

    private static void registrarRotasCaos(Javalin app, Chaos chaos) {

        app.get("/chaos", ctx -> responder(ctx, 200, chaos.estado()));

        app.post("/chaos/particao", ctx -> {
            var corpo = Json.MAPPER.readValue(ctx.body(), new TypeReference<Map<String, List<String>>>() {
            });
            var bloqueados = corpo.getOrDefault("bloqueados", List.of());
            chaos.particionar(Set.copyOf(bloqueados));
            responder(ctx, 200, chaos.estado());
        });

        app.post("/chaos/latencia", ctx -> {
            var corpo = Json.MAPPER.readValue(ctx.body(), new TypeReference<Map<String, Long>>() {
            });
            chaos.definirLatencia(corpo.getOrDefault("ms", 0L), corpo.getOrDefault("jitterMs", 0L));
            responder(ctx, 200, chaos.estado());
        });

        app.post("/chaos/perda", ctx -> {
            var corpo = Json.MAPPER.readValue(ctx.body(), new TypeReference<Map<String, Double>>() {
            });
            chaos.definirPerda(corpo.getOrDefault("probabilidade", 0.0));
            responder(ctx, 200, chaos.estado());
        });

        app.post("/chaos/curar", ctx -> {
            chaos.curar();
            responder(ctx, 200, chaos.estado());
        });
    }

    // ------------------------------------------------------------- diagnostico

    private static void registrarRotasDiagnostico(Javalin app, Config cfg, Store store,
                                                  HashRing ring, Metricas metricas, Chaos chaos) {

        app.get("/health", ctx -> responder(ctx, 200, Map.of(
                "no", cfg.noId(),
                "chaveslocais", store.totalDeChaves(),
                "caosSaudavel", chaos.saudavel())));

        app.get("/metrics", ctx -> ctx.contentType("text/plain; version=0.0.4")
                .result(metricas.scrape()));

        // Onde uma chave mora. Util para escolher, na demo, uma chave cuja
        // lista de preferencia inclua exatamente os nos que vamos derrubar.
        app.get("/debug/anel", ctx -> {
            String chave = ctx.queryParam("chave");
            if (chave == null) {
                responder(ctx, 200, Map.of(
                        "nos", ring.nos(),
                        "vnodes", ring.vnodes(),
                        "distribuicao", ring.distribuicao(10_000)));
            } else {
                responder(ctx, 200, Map.of(
                        "chave", chave,
                        "listaDePreferencia", ring.listaDePreferencia(chave, cfg.n())));
            }
        });

        // Estado local cru desta replica, sem coordenacao. E o que permite
        // mostrar a divergencia entre os dois lados de uma particao.
        app.get("/debug/dump", ctx -> {
            var saida = new java.util.TreeMap<String, Object>();
            store.dump().forEach((chave, versoes) ->
                    saida.put(chave, versoes.stream().map(Dtos.VersaoDto::de).toList()));
            responder(ctx, 200, Map.of(
                    "no", cfg.noId(),
                    "escritasAplicadas", store.escritasAplicadas(),
                    "escritasIgnoradas", store.escritasIgnoradas(),
                    "conflitosGerados", store.conflitosGerados(),
                    "dados", saida));
        });
    }

    // ------------------------------------------------------------------ apoio

    /** Contexto causal que o cliente carrega entre operacoes. */
    private static VersionVector contextoDoHeader(Context ctx) {
        String bruto = ctx.header("X-Contexto");
        if (bruto == null || bruto.isBlank()) {
            bruto = ctx.queryParam("contexto");
        }
        if (bruto == null || bruto.isBlank()) {
            return VersionVector.vazio();
        }
        try {
            Map<String, Long> mapa = Json.MAPPER.readValue(bruto, new TypeReference<>() {
            });
            return new VersionVector(mapa);
        } catch (Exception e) {
            throw new IllegalArgumentException("X-Contexto nao e um vetor JSON valido: " + bruto);
        }
    }

    private static Integer inteiro(Context ctx, String nome) {
        String v = ctx.queryParam(nome);
        if (v == null || v.isBlank()) return null;
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parametro '" + nome + "' deve ser inteiro: " + v);
        }
    }

    private static void responder(Context ctx, int status, Object corpo) {
        ctx.status(status).contentType("application/json").result(Json.escrever(corpo));
    }
}
