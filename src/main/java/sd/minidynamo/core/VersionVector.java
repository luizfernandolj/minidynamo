package sd.minidynamo.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Relogio vetorial: um contador por no que ja escreveu nesta chave.
 *
 * E o mecanismo que permite distinguir "esta escrita e mais nova que aquela"
 * de "estas duas escritas aconteceram em paralelo e nenhuma viu a outra".
 * Timestamp de parede nao serve para isso: relogios de maquinas diferentes
 * divergem, e o Dynamo justamente evita depender deles.
 */
public final class VersionVector {

    public enum Ordem {
        IGUAL,        // mesmos contadores
        DESCENDENTE,  // este aconteceu DEPOIS do outro (domina o outro)
        ANCESTRAL,    // este aconteceu ANTES do outro
        CONCORRENTE   // nenhum viu o outro -> conflito, gera siblings
    }

    private final TreeMap<String, Long> contadores;

    public VersionVector() {
        this.contadores = new TreeMap<>();
    }

    public VersionVector(Map<String, Long> inicial) {
        this.contadores = new TreeMap<>(inicial == null ? Map.of() : inicial);
    }

    public static VersionVector vazio() {
        return new VersionVector();
    }

    /** Devolve uma copia com o contador do no indicado incrementado. */
    public VersionVector incrementar(String noId) {
        var copia = new VersionVector(this.contadores);
        copia.contadores.merge(noId, 1L, Long::sum);
        return copia;
    }

    /** Supremo ponto a ponto: o maximo de cada contador. Usado na reconciliacao. */
    public VersionVector unir(VersionVector outro) {
        var copia = new VersionVector(this.contadores);
        if (outro != null) {
            outro.contadores.forEach((no, v) -> copia.contadores.merge(no, v, Math::max));
        }
        return copia;
    }

    public static VersionVector unirTodos(Collection<VersionVector> vetores) {
        var acc = VersionVector.vazio();
        for (var v : vetores) {
            acc = acc.unir(v);
        }
        return acc;
    }

    public long contadorDe(String noId) {
        return contadores.getOrDefault(noId, 0L);
    }

    public Map<String, Long> comoMapa() {
        return Map.copyOf(contadores);
    }

    public boolean estaVazio() {
        return contadores.isEmpty();
    }

    /**
     * Compara dois vetores. A relacao e de ordem PARCIAL: dois vetores podem
     * simplesmente nao ser comparaveis, e e esse o caso interessante
     * (CONCORRENTE) que o Dynamo expoe para a aplicacao resolver.
     */
    public Ordem comparar(VersionVector outro) {
        if (outro == null) {
            return contadores.isEmpty() ? Ordem.IGUAL : Ordem.DESCENDENTE;
        }
        Set<String> nos = new HashSet<>(contadores.keySet());
        nos.addAll(outro.contadores.keySet());

        boolean algumMaior = false;
        boolean algumMenor = false;
        for (String no : nos) {
            long a = this.contadorDe(no);
            long b = outro.contadorDe(no);
            if (a > b) algumMaior = true;
            if (a < b) algumMenor = true;
        }
        if (algumMaior && algumMenor) return Ordem.CONCORRENTE;
        if (algumMaior) return Ordem.DESCENDENTE;
        if (algumMenor) return Ordem.ANCESTRAL;
        return Ordem.IGUAL;
    }

    /** true se este vetor ja "viu" tudo o que o outro viu (inclui ser igual). */
    public boolean dominaOuIgual(VersionVector outro) {
        var o = comparar(outro);
        return o == Ordem.DESCENDENTE || o == Ordem.IGUAL;
    }

    @Override
    public String toString() {
        return contadores.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VersionVector v && contadores.equals(v.contadores);
    }

    @Override
    public int hashCode() {
        return contadores.hashCode();
    }
}
