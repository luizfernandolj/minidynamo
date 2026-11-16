package sd.minidynamo.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuracao do no, toda por variavel de ambiente para que o
 * docker-compose.yml seja a unica fonte da topologia.
 *
 * PEERS usa a forma "id@host:porta,id@host:porta,...". O proprio no aparece na
 * lista: assim todos os containers recebem exatamente a mesma string e nada
 * precisa ser calculado por no.
 */
public record Config(
        String noId,
        int porta,
        Map<String, String> peers,   // id -> "host:porta"
        int n,
        int r,
        int w,
        int vnodes,
        long rpcTimeoutMs,
        long replicacaoTimeoutMs,
        long entregaDeHintsMs,
        long antiEntropiaMs,
        boolean antiEntropiaLigada
) {

    public static Config doAmbiente() {
        String noId = env("NODE_ID", "node1");
        int porta = Integer.parseInt(env("HTTP_PORT", "8080"));
        String peersRaw = env("PEERS", noId + "@127.0.0.1:" + porta);

        var peers = new LinkedHashMap<String, String>();
        for (String parte : peersRaw.split(",")) {
            String p = parte.trim();
            if (p.isEmpty()) continue;
            int at = p.indexOf('@');
            if (at < 0) {
                throw new IllegalArgumentException(
                        "PEERS malformado em '" + p + "'. Esperado id@host:porta");
            }
            peers.put(p.substring(0, at).trim(), p.substring(at + 1).trim());
        }
        if (!peers.containsKey(noId)) {
            throw new IllegalArgumentException(
                    "NODE_ID '" + noId + "' nao aparece em PEERS=" + peersRaw);
        }

        int n = Integer.parseInt(env("REPLICATION_N", "3"));
        int quorum = n / 2 + 1;
        int r = Integer.parseInt(env("DEFAULT_R", String.valueOf(quorum)));
        int w = Integer.parseInt(env("DEFAULT_W", String.valueOf(quorum)));

        return new Config(
                noId,
                porta,
                Map.copyOf(peers),
                n,
                r,
                w,
                Integer.parseInt(env("VNODES", "128")),
                Long.parseLong(env("RPC_TIMEOUT_MS", "800")),
                Long.parseLong(env("REPLICATION_TIMEOUT_MS", "1500")),
                Long.parseLong(env("HINT_DELIVERY_MS", "2000")),
                Long.parseLong(env("ANTI_ENTROPY_MS", "5000")),
                Boolean.parseBoolean(env("ANTI_ENTROPY_ENABLED", "true"))
        );
    }

    public List<String> idsDosNos() {
        return new ArrayList<>(peers.keySet());
    }

    public String enderecoDe(String id) {
        return peers.get(id);
    }

    public int quorumPadrao() {
        return n / 2 + 1;
    }

    private static String env(String chave, String padrao) {
        String v = System.getenv(chave);
        return (v == null || v.isBlank()) ? padrao : v.trim();
    }
}
