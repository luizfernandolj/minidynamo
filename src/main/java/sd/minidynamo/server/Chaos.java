package sd.minidynamo.server;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Injecao de falhas no proprio processo, em vez de iptables ou tc.
 *
 * A razao e portabilidade: a equipe desenvolve no Windows e a demo roda em
 * container. Mexer em regra de firewall dentro do container exigiria
 * NET_ADMIN e nao funcionaria igual nas tres maquinas. Aqui a falha e um
 * estado do no, alteravel por HTTP ao vivo durante a apresentacao.
 *
 * Importante para o relatorio: isto simula a PERDA DE MENSAGEM, nao a queda do
 * processo. A queda real se demonstra com 'docker compose stop rj' -- e os
 * dois cenarios tem comportamentos diferentes, o que vale discutir.
 */
public final class Chaos {

    /** Destinos para os quais este no finge nao conseguir falar (particao). */
    private final Set<String> bloqueados = ConcurrentHashMap.newKeySet();

    private volatile long latenciaMs = 0;
    private volatile long jitterMs = 0;
    private volatile double probabilidadeDePerda = 0.0;

    /**
     * Chamado antes de cada RPC. Aplica atraso e decide se a mensagem "se
     * perde". Lanca IOException para que o caminho de erro exercitado seja
     * exatamente o mesmo de uma falha de rede de verdade.
     */
    public void antesDeEnviar(String destino) throws IOException {
        if (bloqueados.contains(destino)) {
            throw new IOException("particao de rede simulada: " + destino + " inalcancavel");
        }
        if (probabilidadeDePerda > 0 && ThreadLocalRandom.current().nextDouble() < probabilidadeDePerda) {
            throw new IOException("mensagem descartada (perda simulada) para " + destino);
        }
        long atraso = latenciaMs;
        if (jitterMs > 0) {
            atraso += ThreadLocalRandom.current().nextLong(jitterMs + 1);
        }
        if (atraso > 0) {
            try {
                Thread.sleep(atraso);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrompido durante atraso simulado", e);
            }
        }
    }

    public void particionar(Set<String> destinos) {
        bloqueados.clear();
        bloqueados.addAll(destinos);
    }

    public void definirLatencia(long ms, long jitter) {
        this.latenciaMs = Math.max(0, ms);
        this.jitterMs = Math.max(0, jitter);
    }

    public void definirPerda(double probabilidade) {
        this.probabilidadeDePerda = Math.min(1.0, Math.max(0.0, probabilidade));
    }

    public void curar() {
        bloqueados.clear();
        latenciaMs = 0;
        jitterMs = 0;
        probabilidadeDePerda = 0.0;
    }

    public boolean saudavel() {
        return bloqueados.isEmpty() && latenciaMs == 0 && probabilidadeDePerda == 0.0;
    }

    public Map<String, Object> estado() {
        return Map.of(
                "bloqueados", Set.copyOf(bloqueados),
                "latenciaMs", latenciaMs,
                "jitterMs", jitterMs,
                "probabilidadeDePerda", probabilidadeDePerda,
                "saudavel", saudavel()
        );
    }
}
