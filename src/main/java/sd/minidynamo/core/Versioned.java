package sd.minidynamo.core;

/**
 * Um valor acompanhado do relogio vetorial que o datou.
 *
 * O campo gravadoEm e so para observabilidade (logs e metricas): a ordenacao
 * causal vem do vetor, nunca do horario de parede.
 *
 * O campo tombstone e a marca de remocao. Um delete nao apaga o registro de
 * imediato porque a replica que estava fora do ar voltaria a propagar o valor
 * antigo na volta; a remocao precisa de uma versao propria para vencer a
 * escrita anterior.
 */
public record Versioned(String valor, VersionVector relogio, long gravadoEm, boolean tombstone) {

    public static Versioned de(String valor, VersionVector relogio) {
        return new Versioned(valor, relogio, System.currentTimeMillis(), false);
    }

    public static Versioned remocao(VersionVector relogio) {
        return new Versioned(null, relogio, System.currentTimeMillis(), true);
    }
}
