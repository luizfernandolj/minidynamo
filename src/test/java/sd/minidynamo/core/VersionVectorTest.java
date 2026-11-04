package sd.minidynamo.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionVectorTest {

    @Test
    @DisplayName("escrita sequencial no mesmo no gera descendencia")
    void sequencialDescende() {
        var v1 = VersionVector.vazio().incrementar("node1");
        var v2 = v1.incrementar("node1");

        assertEquals(VersionVector.Ordem.DESCENDENTE, v2.comparar(v1));
        assertEquals(VersionVector.Ordem.ANCESTRAL, v1.comparar(v2));
    }

    @Test
    @DisplayName("escritas cegas em nos diferentes sao concorrentes")
    void cegasSaoConcorrentes() {
        // Dois clientes leem o mesmo estado (vazio) e escrevem sem se ver.
        var a = VersionVector.vazio().incrementar("node1");
        var b = VersionVector.vazio().incrementar("node2");

        assertEquals(VersionVector.Ordem.CONCORRENTE, a.comparar(b));
        assertEquals(VersionVector.Ordem.CONCORRENTE, b.comparar(a));
        assertFalse(a.dominaOuIgual(b));
        assertFalse(b.dominaOuIgual(a));
    }

    @Test
    @DisplayName("escrita com contexto de leitura resolve o conflito")
    void comContextoNaoConflita() {
        var a = VersionVector.vazio().incrementar("node1");
        var b = VersionVector.vazio().incrementar("node2");

        // O cliente leu os dois siblings e escreveu declarando ter visto ambos.
        var resolvido = a.unir(b).incrementar("node3");

        assertEquals(VersionVector.Ordem.DESCENDENTE, resolvido.comparar(a));
        assertEquals(VersionVector.Ordem.DESCENDENTE, resolvido.comparar(b));
    }

    @Test
    @DisplayName("uniao toma o maximo de cada contador")
    void uniaoTomaMaximo() {
        var a = new VersionVector(Map.of("node1", 5L, "node2", 1L));
        var b = new VersionVector(Map.of("node1", 2L, "node3", 7L));

        var u = a.unir(b);

        assertEquals(5L, u.contadorDe("node1"));
        assertEquals(1L, u.contadorDe("node2"));
        assertEquals(7L, u.contadorDe("node3"));
    }

    @Test
    @DisplayName("vetores iguais sao IGUAL e dominam mutuamente")
    void iguais() {
        var a = new VersionVector(Map.of("node1", 3L));
        var b = new VersionVector(Map.of("node1", 3L));

        assertEquals(VersionVector.Ordem.IGUAL, a.comparar(b));
        assertTrue(a.dominaOuIgual(b));
        assertTrue(b.dominaOuIgual(a));
    }

    @Test
    @DisplayName("vazio e ancestral de qualquer vetor nao vazio")
    void vazioEAncestral() {
        var vazio = VersionVector.vazio();
        var algum = VersionVector.vazio().incrementar("node1");

        assertEquals(VersionVector.Ordem.ANCESTRAL, vazio.comparar(algum));
        assertTrue(algum.dominaOuIgual(vazio));
    }

    @Test
    @DisplayName("incrementar nao altera o vetor original")
    void incrementarEImutavel() {
        var original = VersionVector.vazio().incrementar("node1");
        var derivado = original.incrementar("node1");

        assertEquals(1L, original.contadorDe("node1"));
        assertEquals(2L, derivado.contadorDe("node1"));
    }
}
