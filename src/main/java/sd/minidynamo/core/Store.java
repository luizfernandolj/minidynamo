package sd.minidynamo.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Armazenamento local de UMA replica. Em memoria, de proposito: o trabalho
 * estuda consistencia e nao durabilidade, e manter tudo em memoria faz o no
 * subir em ~300 ms, o que importa quando se mede tempo de recuperacao.
 *
 * Nao coordena nada e nao conhece quorum. Quem orquestra replicas e o
 * Coordenador; aqui so se aplica a regra de dominancia causal.
 */
public final class Store {

    private final ConcurrentHashMap<String, List<Versioned>> dados = new ConcurrentHashMap<>();
    private final AtomicLong escritasAplicadas = new AtomicLong();
    private final AtomicLong escritasIgnoradas = new AtomicLong();
    private final AtomicLong conflitosGerados = new AtomicLong();

    /** Versoes vivas da chave. Mais de uma significa conflito nao resolvido. */
    public List<Versioned> ler(String chave) {
        return dados.getOrDefault(chave, List.of());
    }

    /**
     * Aplica uma versao recebida (de um cliente via coordenador, de outra
     * replica, ou de read repair). A operacao e idempotente: reenviar a mesma
     * versao nao muda nada, o que e essencial porque retry e perda de resposta
     * acontecem o tempo todo.
     *
     * @return as versoes vivas depois da aplicacao
     */
    public List<Versioned> gravar(String chave, Versioned nova) {
        return dados.compute(chave, (k, atuais) -> {
            var entrada = new ArrayList<Versioned>();
            if (atuais != null) entrada.addAll(atuais);
            entrada.add(nova);

            var resultado = Reconciliador.reconciliar(entrada);

            boolean aceitou = resultado.stream().anyMatch(v -> v.relogio().equals(nova.relogio()));
            if (aceitou) {
                escritasAplicadas.incrementAndGet();
            } else {
                escritasIgnoradas.incrementAndGet();
            }
            if (resultado.size() > 1) {
                conflitosGerados.incrementAndGet();
            }
            return List.copyOf(resultado);
        });
    }

    /** Aplica um lote de versoes. Usado por read repair e anti-entropia. */
    public void gravarTodas(String chave, Collection<Versioned> versoes) {
        for (Versioned v : versoes) {
            gravar(chave, v);
        }
    }

    /**
     * O vetor que esta replica conhece para a chave. O coordenador parte dele
     * para datar uma escrita nova quando o cliente nao mandou contexto.
     */
    public VersionVector relogioDe(String chave) {
        return Reconciliador.contextoDe(ler(chave));
    }

    public Map<String, List<Versioned>> dump() {
        return Map.copyOf(dados);
    }

    public int totalDeChaves() {
        return dados.size();
    }

    public long escritasAplicadas() {
        return escritasAplicadas.get();
    }

    public long escritasIgnoradas() {
        return escritasIgnoradas.get();
    }

    public long conflitosGerados() {
        return conflitosGerados.get();
    }
}
