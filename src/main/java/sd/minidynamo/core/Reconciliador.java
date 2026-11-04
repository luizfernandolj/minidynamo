package sd.minidynamo.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reduz um amontoado de versoes vindas de varias replicas ao conjunto minimo.
 *
 * A regra e unica: descarta toda versao que seja ANCESTRAL de outra, porque
 * alguem ja a viu e escreveu depois. O que sobra e ou uma unica versao
 * vencedora, ou duas ou mais versoes CONCORRENTES -- os siblings, que o
 * Dynamo devolve para a aplicacao resolver.
 *
 * Note o que NAO fazemos aqui: escolher pelo horario mais recente. Isso seria
 * last-writer-wins e descartaria silenciosamente a escrita concorrente de
 * alguem. Perder dado calado e pior que devolver conflito.
 */
public final class Reconciliador {

    private Reconciliador() {
    }

    public static List<Versioned> reconciliar(Collection<Versioned> todas) {
        var candidatas = new ArrayList<Versioned>();

        for (Versioned nova : todas) {
            if (nova == null) continue;

            boolean dominada = false;
            var sobreviventes = new ArrayList<Versioned>();

            for (Versioned ja : candidatas) {
                switch (nova.relogio().comparar(ja.relogio())) {
                    case ANCESTRAL, IGUAL -> {
                        // 'nova' nao acrescenta nada: 'ja' e igual ou mais recente.
                        dominada = true;
                        sobreviventes.add(ja);
                    }
                    case DESCENDENTE -> {
                        // 'nova' viu 'ja' e escreveu depois: 'ja' morre.
                    }
                    case CONCORRENTE -> sobreviventes.add(ja);
                }
            }
            candidatas = sobreviventes;
            if (!dominada) {
                candidatas.add(nova);
            }
        }
        return candidatas;
    }

    /** O vetor que resume tudo o que o conjunto conhece. Vira o contexto do cliente. */
    public static VersionVector contextoDe(Collection<Versioned> versoes) {
        return VersionVector.unirTodos(versoes.stream().map(Versioned::relogio).toList());
    }
}
