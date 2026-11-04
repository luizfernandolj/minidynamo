package sd.minidynamo.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Hashing consistente com nos virtuais.
 *
 * Cada no fisico aparece varias vezes no anel (nos virtuais). Sem isso a carga
 * fica torta: com 5 nos sorteados uma unica vez, um deles pode ficar com 40%
 * do espaco de chaves. Com 128 vnodes por no a distribuicao fica proxima do
 * uniforme, e a saida de um no redistribui sua fatia entre varios vizinhos em
 * vez de despejar tudo no proximo.
 */
public final class HashRing {

    private final SortedMap<Long, String> anel = new TreeMap<>();
    private final List<String> nosFisicos;
    private final int vnodes;

    public HashRing(Collection<String> nosFisicos, int vnodes) {
        this.nosFisicos = List.copyOf(nosFisicos);
        this.vnodes = vnodes;
        for (String no : this.nosFisicos) {
            for (int i = 0; i < vnodes; i++) {
                anel.put(hash(no + "#" + i), no);
            }
        }
    }

    /**
     * Lista de preferencia da chave: os n primeiros nos FISICOS distintos a
     * partir da posicao da chave, caminhando no sentido do anel. O primeiro da
     * lista e o dono natural da chave.
     */
    public List<String> listaDePreferencia(String chave, int n) {
        if (anel.isEmpty()) return List.of();
        int alvo = Math.min(n, nosFisicos.size());

        var escolhidos = new LinkedHashSet<String>();
        long pos = hash(chave);

        // Primeiro os vnodes a partir da posicao da chave, depois da-se a volta.
        for (String no : anel.tailMap(pos).values()) {
            if (escolhidos.size() == alvo) break;
            escolhidos.add(no);
        }
        for (String no : anel.headMap(pos).values()) {
            if (escolhidos.size() == alvo) break;
            escolhidos.add(no);
        }
        return new ArrayList<>(escolhidos);
    }

    /** Quantas chaves de uma amostra caem como dono em cada no. Para o relatorio. */
    public Map<String, Integer> distribuicao(int amostras) {
        var contagem = new TreeMap<String, Integer>();
        for (String no : nosFisicos) contagem.put(no, 0);
        for (int i = 0; i < amostras; i++) {
            var lista = listaDePreferencia("chave-" + i, 1);
            if (!lista.isEmpty()) contagem.merge(lista.get(0), 1, Integer::sum);
        }
        return contagem;
    }

    public List<String> nos() {
        return nosFisicos;
    }

    public int vnodes() {
        return vnodes;
    }

    /** SHA-1 truncado em 64 bits. Nao e criptografia: so precisa espalhar bem. */
    static long hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (d[i] & 0xffL);
            }
            return h;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 indisponivel nesta JVM", e);
        }
    }
}
