package sd.minidynamo.server;

import sd.minidynamo.core.Versioned;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Chamadas a API interna das outras replicas.
 *
 * Todo envio passa primeiro pelo Chaos, para que uma particao configurada por
 * HTTP se manifeste aqui como IOException -- o mesmo caminho de erro de uma
 * falha de rede real.
 */
public final class PeerClient {

    private final Config cfg;
    private final Chaos chaos;
    private final HttpClient http;

    public PeerClient(Config cfg, Chaos chaos, java.util.concurrent.Executor executor) {
        this.cfg = cfg;
        this.chaos = chaos;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.rpcTimeoutMs()))
                .executor(executor)
                .build();
    }

    public List<Versioned> lerRemoto(String noId, String chave) throws Exception {
        chaos.antesDeEnviar(noId);

        var req = HttpRequest.newBuilder(uri(noId, "/internal/kv/" + enc(chave)))
                .timeout(Duration.ofMillis(cfg.rpcTimeoutMs()))
                .GET()
                .build();

        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("replica " + noId + " respondeu " + resp.statusCode());
        }
        var corpo = Json.ler(resp.body(), Dtos.RespostaInterna.class);
        return corpo.versoes().stream().map(Dtos.VersaoDto::paraDominio).toList();
    }

    public void gravarRemoto(String noId, String chave, Versioned versao) throws Exception {
        chaos.antesDeEnviar(noId);

        String corpo = Json.escrever(new Dtos.PedidoReplicacao(Dtos.VersaoDto.de(versao)));
        var req = HttpRequest.newBuilder(uri(noId, "/internal/kv/" + enc(chave)))
                .timeout(Duration.ofMillis(cfg.rpcTimeoutMs()))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(corpo))
                .build();

        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("replica " + noId + " respondeu " + resp.statusCode());
        }
    }

    /**
     * Entrega a escrita a um SUBSTITUTO, com a dica de quem era o dono.
     * O substituto guarda e reentrega quando {@code donoOriginal} voltar.
     */
    public void gravarHint(String substituto, String donoOriginal, String chave, Versioned versao)
            throws Exception {
        chaos.antesDeEnviar(substituto);

        String corpo = Json.escrever(new Dtos.PedidoReplicacao(Dtos.VersaoDto.de(versao)));
        var req = HttpRequest.newBuilder(
                        uri(substituto, "/internal/hint/" + enc(donoOriginal) + "/" + enc(chave)))
                .timeout(Duration.ofMillis(cfg.rpcTimeoutMs()))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(corpo))
                .build();

        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("substituto " + substituto + " respondeu " + resp.statusCode());
        }
    }

    /** Resumo do que o peer conhece, por chave. Nao transfere valores. */
    public Dtos.Digest digestRemoto(String noId) throws Exception {
        chaos.antesDeEnviar(noId);

        var req = HttpRequest.newBuilder(uri(noId, "/internal/digest"))
                .timeout(Duration.ofMillis(cfg.rpcTimeoutMs()))
                .GET()
                .build();

        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("peer " + noId + " respondeu " + resp.statusCode());
        }
        return Json.ler(resp.body(), Dtos.Digest.class);
    }

    private URI uri(String noId, String caminho) {
        String endereco = cfg.enderecoDe(noId);
        if (endereco == null) {
            throw new IllegalArgumentException("no desconhecido: " + noId);
        }
        return URI.create("http://" + endereco + caminho);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
