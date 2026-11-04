package sd.minidynamo.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {

    @Test
    @DisplayName("sobrescreve quando a nova versao descende da atual")
    void sobrescreveDescendente() {
        var store = new Store();
        var v1 = VersionVector.vazio().incrementar("node1");

        store.gravar("k", Versioned.de("primeiro", v1));
        store.gravar("k", Versioned.de("segundo", v1.incrementar("node1")));

        assertEquals(1, store.ler("k").size());
        assertEquals("segundo", store.ler("k").get(0).valor());
    }

    @Test
    @DisplayName("replica atrasada nao ressuscita valor antigo")
    void ignoraAncestral() {
        var store = new Store();
        var v1 = VersionVector.vazio().incrementar("node1");
        var v2 = v1.incrementar("node1");

        store.gravar("k", Versioned.de("novo", v2));
        store.gravar("k", Versioned.de("antigo", v1));   // chega atrasado

        assertEquals(1, store.ler("k").size());
        assertEquals("novo", store.ler("k").get(0).valor());
        assertEquals(1, store.escritasIgnoradas());
    }

    @Test
    @DisplayName("escritas concorrentes convivem como siblings")
    void guardaSiblings() {
        var store = new Store();

        store.gravar("carrinho", Versioned.de("A", VersionVector.vazio().incrementar("node1")));
        store.gravar("carrinho", Versioned.de("B", VersionVector.vazio().incrementar("node2")));

        assertEquals(2, store.ler("carrinho").size());
        assertEquals(1, store.conflitosGerados());
    }

    @Test
    @DisplayName("reaplicar a mesma versao e idempotente")
    void idempotente() {
        var store = new Store();
        var versao = Versioned.de("x", VersionVector.vazio().incrementar("node1"));

        store.gravar("k", versao);
        store.gravar("k", versao);
        store.gravar("k", versao);

        assertEquals(1, store.ler("k").size());
    }

    @Test
    @DisplayName("relogio local resume os siblings existentes")
    void relogioLocalResume() {
        var store = new Store();
        store.gravar("k", Versioned.de("A", VersionVector.vazio().incrementar("node1")));
        store.gravar("k", Versioned.de("B", VersionVector.vazio().incrementar("node2")));

        var relogio = store.relogioDe("k");

        assertEquals(1L, relogio.contadorDe("node1"));
        assertEquals(1L, relogio.contadorDe("node2"));
    }

    @Test
    @DisplayName("tombstone vence a escrita anterior")
    void remocaoVence() {
        var store = new Store();
        var v1 = VersionVector.vazio().incrementar("node1");
        store.gravar("k", Versioned.de("vivo", v1));

        store.gravar("k", Versioned.remocao(v1.incrementar("node1")));

        var versoes = store.ler("k");
        assertEquals(1, versoes.size());
        assertTrue(versoes.get(0).tombstone());
    }

    @Test
    @DisplayName("chave inexistente devolve lista vazia, nao null")
    void chaveInexistente() {
        assertTrue(new Store().ler("nao-existe").isEmpty());
    }
}
