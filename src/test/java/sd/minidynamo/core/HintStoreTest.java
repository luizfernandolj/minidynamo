package sd.minidynamo.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HintStoreTest {

    private static Versioned versao(String valor, String no) {
        return Versioned.de(valor, VersionVector.vazio().incrementar(no));
    }

    @Test
    @DisplayName("guarda a escrita em nome do no que estava fora")
    void guardaEmNomeDeOutro() {
        var hints = new HintStore();

        hints.guardar("node3", "k", versao("v", "node1"));

        assertEquals(1, hints.total());
        assertEquals(List.of("node3"), hints.destinosPendentes());
        assertEquals(1, hints.pendentesPara("node3").size());
    }

    @Test
    @DisplayName("hint repetido nao duplica trabalho de entrega")
    void idempotente() {
        var hints = new HintStore();
        var v = versao("v", "node1");

        hints.guardar("node3", "k", v);
        hints.guardar("node3", "k", v);
        hints.guardar("node3", "k", v);

        assertEquals(1, hints.total());
        assertEquals(1, hints.guardados());
    }

    @Test
    @DisplayName("versoes diferentes da mesma chave sao hints distintos")
    void versoesDistintas() {
        var hints = new HintStore();

        hints.guardar("node3", "k", versao("primeiro", "node1"));
        hints.guardar("node3", "k", versao("segundo", "node2"));

        assertEquals(2, hints.total());
    }

    @Test
    @DisplayName("entregar remove da fila e contabiliza")
    void entregaRemove() {
        var hints = new HintStore();
        hints.guardar("node3", "k", versao("v", "node1"));
        var hint = hints.pendentesPara("node3").get(0);

        hints.marcarEntregue(hint);

        assertEquals(0, hints.total());
        assertEquals(1, hints.entregues());
        assertTrue(hints.destinosPendentes().isEmpty());
    }

    @Test
    @DisplayName("entregar duas vezes nao conta duas entregas")
    void entregaDuplaNaoContaDuasVezes() {
        var hints = new HintStore();
        hints.guardar("node3", "k", versao("v", "node1"));
        var hint = hints.pendentesPara("node3").get(0);

        hints.marcarEntregue(hint);
        hints.marcarEntregue(hint);

        assertEquals(1, hints.entregues());
    }

    @Test
    @DisplayName("resumo separa os pendentes por destino")
    void resumoPorDestino() {
        var hints = new HintStore();
        hints.guardar("node3", "a", versao("1", "node1"));
        hints.guardar("node3", "b", versao("2", "node1"));
        hints.guardar("node4", "c", versao("3", "node1"));

        var resumo = hints.resumo();

        assertEquals(2, resumo.get("node3"));
        assertEquals(1, resumo.get("node4"));
    }

    @Test
    @DisplayName("destino sem hints nao aparece no resumo")
    void destinoVazioSomeDoResumo() {
        var hints = new HintStore();
        hints.guardar("node3", "k", versao("v", "node1"));
        hints.marcarEntregue(hints.pendentesPara("node3").get(0));

        assertTrue(hints.resumo().isEmpty());
    }
}
