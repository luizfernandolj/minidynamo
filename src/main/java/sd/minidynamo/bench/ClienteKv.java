package sd.minidynamo.bench;

import sd.minidynamo.server.Dtos;
import sd.minidynamo.server.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Cliente HTTP do MiniDynamo usado pelo bench.
 *
 * Fala com um no qualquer: como nao existe mestre, qualquer no coordena
 * qualquer chave. O bench explora isso de proposito -- escreve em um no e le
 * em outro -- porque e nesse vai-e-vem que a inconsistencia aparece.
 */
public final class ClienteKv {

    public record Resultado<T>(int status, T corpo, long latenciaNanos, String erro) {
        public boolean ok() {
            return erro == null && status < 400;
        }
    }

    private final HttpClient http;
    private final Duration timeout;

    public ClienteKv(java.util.concurrent.Executor executor, Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .executor(executor)
                .build();
    }

    public Resultado<Dtos.RespostaEscrita> escrever(String baseUrl, String chave, String valor,
                                                    Map<String, Long> contexto, String modo, Integer w,
                                                    Boolean sloppy) {
        String url = baseUrl + "/kv/" + enc(chave) + "?modo=" + modo
                + (w == null ? "" : "&w=" + w)
                + (sloppy == null ? "" : "&sloppy=" + sloppy);
        String corpo = Json.escrever(new Dtos.PedidoEscrita(valor, contexto));

        var req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(corpo))
                .build();

        return executar(req, Dtos.RespostaEscrita.class);
    }

    public Resultado<Dtos.RespostaLeitura> ler(String baseUrl, String chave,
                                               Map<String, Long> contexto, String modo, Integer r) {
        String url = baseUrl + "/kv/" + enc(chave) + "?modo=" + modo + (r == null ? "" : "&r=" + r);

        var b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
        if (contexto != null && !contexto.isEmpty()) {
            b.header("X-Contexto", Json.escrever(contexto));
        }
        return executar(b.build(), Dtos.RespostaLeitura.class);
    }

    private <T> Resultado<T> executar(HttpRequest req, Class<T> tipo) {
        long t0 = System.nanoTime();
        try {
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long dt = System.nanoTime() - t0;
            // 404/409/503 tambem trazem corpo util: sao respostas do sistema,
            // nao falhas de transporte.
            try {
                return new Resultado<>(resp.statusCode(), Json.ler(resp.body(), tipo), dt, null);
            } catch (Exception semCorpo) {
                return new Resultado<>(resp.statusCode(), null, dt, null);
            }
        } catch (Exception e) {
            return new Resultado<>(0, null, System.nanoTime() - t0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
