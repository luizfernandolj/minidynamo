package sd.minidynamo.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliadorTest {

    @Test
    @DisplayName("descarta versao que alguem ja superou")
    void descartaAncestral() {
        var v1 = VersionVector.vazio().incrementar("node1");
        var v2 = v1.incrementar("node1");

        var r = Reconciliador.reconciliar(List.of(
                Versioned.de("antigo", v1),
                Versioned.de("novo", v2)));

        assertEquals(1, r.size());
        assertEquals("novo", r.get(0).valor());
    }

    @Test
    @DisplayName("mantem as duas versoes quando sao concorrentes (siblings)")
    void mantemSiblings() {
        var a = VersionVector.vazio().incrementar("node1");
        var b = VersionVector.vazio().incrementar("node2");

        var r = Reconciliador.reconciliar(List.of(
                Versioned.de("carrinho-A", a),
                Versioned.de("carrinho-B", b)));

        assertEquals(2, r.size());
        assertTrue(r.stream().anyMatch(v -> v.valor().equals("carrinho-A")));
        assertTrue(r.stream().anyMatch(v -> v.valor().equals("carrinho-B")));
    }

    @Test
    @DisplayName("ordem de chegada das replicas nao altera o resultado")
    void ordemNaoImporta() {
        var a = VersionVector.vazio().incrementar("node1");
        var b = VersionVector.vazio().incrementar("node2");
        var resolvido = a.unir(b).incrementar("node1");

        var versoes = List.of(
                Versioned.de("A", a),
                Versioned.de("B", b),
                Versioned.de("resolvido", resolvido));

        var direta = Reconciliador.reconciliar(versoes);
        var invertida = Reconciliador.reconciliar(versoes.reversed());

        assertEquals(1, direta.size());
        assertEquals(1, invertida.size());
        assertEquals("resolvido", direta.get(0).valor());
        assertEquals("resolvido", invertida.get(0).valor());
    }

    @Test
    @DisplayName("versao repetida nao duplica")
    void idempotente() {
        var v = VersionVector.vazio().incrementar("node1");
        var versao = Versioned.de("x", v);

        var r = Reconciliador.reconciliar(List.of(versao, versao, versao));

        assertEquals(1, r.size());
    }

    @Test
    @DisplayName("contexto resume tudo que o conjunto conhece")
    void contextoResume() {
        var a = VersionVector.vazio().incrementar("node1");
        var b = VersionVector.vazio().incrementar("node2");

        var ctx = Reconciliador.contextoDe(List.of(
                Versioned.de("A", a), Versioned.de("B", b)));

        assertEquals(1L, ctx.contadorDe("node1"));
        assertEquals(1L, ctx.contadorDe("node2"));
    }

    @Test
    @DisplayName("conjunto vazio reconcilia para vazio")
    void vazio() {
        assertTrue(Reconciliador.reconciliar(List.of()).isEmpty());
    }
}
