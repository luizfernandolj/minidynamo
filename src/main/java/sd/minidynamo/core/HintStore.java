package sd.minidynamo.core;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Escritas que este no aceitou guardar EM NOME de outro que estava fora do ar.
 *
 * Esse e o mecanismo de hinted handoff do Dynamo (secao 4.6). Quando a replica
 * certa nao responde, o coordenador nao desiste: entrega a escrita a um
 * substituto junto com a "dica" de quem era o dono. O substituto nao serve
 * essa chave em leituras -- ele apenas segura o dado ate conseguir entregar.
 *
 * O ganho e disponibilidade de escrita durante falha. O preco esta documentado
 * no README: com substitutos, R+W > N deixa de garantir intersecao, porque as
 * replicas que confirmaram podem nao ser as da lista de preferencia.
 */
public final class HintStore {

    public record Hint(String destino, String chave, Versioned versao) {
    }

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Hint>> porDestino =
            new ConcurrentHashMap<>();

    private final AtomicLong guardados = new AtomicLong();
    private final AtomicLong entregues = new AtomicLong();

    public void guardar(String destino, String chave, Versioned versao) {
        var hint = new Hint(destino, chave, versao);
        var fila = porDestino.computeIfAbsent(destino, d -> new CopyOnWriteArrayList<>());
        // Idempotente: reenvio do mesmo hint nao duplica trabalho de entrega.
        if (!fila.contains(hint)) {
            fila.add(hint);
            guardados.incrementAndGet();
        }
    }

    public List<String> destinosPendentes() {
        return porDestino.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
    }

    public List<Hint> pendentesPara(String destino) {
        return List.copyOf(porDestino.getOrDefault(destino, new CopyOnWriteArrayList<>()));
    }

    public void marcarEntregue(Hint hint) {
        var fila = porDestino.get(hint.destino());
        if (fila != null && fila.remove(hint)) {
            entregues.incrementAndGet();
        }
    }

    public int total() {
        return porDestino.values().stream().mapToInt(List::size).sum();
    }

    public Map<String, Integer> resumo() {
        var saida = new TreeMap<String, Integer>();
        porDestino.forEach((destino, fila) -> {
            if (!fila.isEmpty()) saida.put(destino, fila.size());
        });
        return saida;
    }

    public long guardados() {
        return guardados.get();
    }

    public long entregues() {
        return entregues.get();
    }
}
