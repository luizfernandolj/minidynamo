package sd.minidynamo.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashRingTest {

    private static final List<String> CINCO =
            List.of("node1", "node2", "node3", "node4", "node5");

    @Test
    @DisplayName("lista de preferencia tem N nos fisicos distintos")
    void prefListaSemRepetir() {
        var ring = new HashRing(CINCO, 128);

        for (int i = 0; i < 200; i++) {
            var pref = ring.listaDePreferencia("chave-" + i, 3);
            assertEquals(3, pref.size());
            assertEquals(3, Set.copyOf(pref).size(), "nos repetidos em " + pref);
        }
    }

    @Test
    @DisplayName("mesma chave sempre cai na mesma lista: o anel e deterministico")
    void deterministico() {
        var a = new HashRing(CINCO, 128);
        var b = new HashRing(CINCO, 128);

        assertEquals(a.listaDePreferencia("carrinho:42", 3),
                     b.listaDePreferencia("carrinho:42", 3));
    }

    @Test
    @DisplayName("N maior que o cluster devolve todos os nos, sem estourar")
    void nMaiorQueCluster() {
        var ring = new HashRing(List.of("node1", "node2", "node3"), 64);

        var pref = ring.listaDePreferencia("x", 10);

        assertEquals(3, pref.size());
    }

    @Test
    @DisplayName("vnodes distribuem a carga de forma aproximadamente uniforme")
    void distribuicaoUniforme() {
        var ring = new HashRing(CINCO, 128);
        int amostras = 20_000;
        int ideal = amostras / 5;

        var dist = ring.distribuicao(amostras);

        // Com 128 vnodes por no, 25% de desvio sobre o ideal e folga confortavel.
        // E exatamente esse numero que justifica usar nos virtuais no relatorio.
        dist.forEach((no, qtd) -> assertTrue(
                Math.abs(qtd - ideal) < ideal * 0.25,
                "no " + no + " ficou com " + qtd + " chaves (ideal ~" + ideal + ")"));
    }

    @Test
    @DisplayName("sair um no realoca sua fatia sem embaralhar o resto")
    void saidaDeNoRealocaPouco() {
        var antes = new HashRing(CINCO, 128);
        var depois = new HashRing(List.of("node1", "node2", "node3", "node4"), 128);

        int mudaram = 0;
        int amostras = 5_000;
        for (int i = 0; i < amostras; i++) {
            String chave = "chave-" + i;
            var donoAntes = antes.listaDePreferencia(chave, 1).get(0);
            var donoDepois = depois.listaDePreferencia(chave, 1).get(0);
            if (!donoAntes.equals(donoDepois)) mudaram++;
        }

        // So as chaves do node5 deveriam trocar de dono: ~1/5 do total.
        // Hashing consistente garante O(K/N) realocacao, nao O(K).
        double fracao = (double) mudaram / amostras;
        assertTrue(fracao < 0.30, "realocou " + (fracao * 100) + "% das chaves");
    }

    @Test
    @DisplayName("anel vazio nao explode")
    void anelVazio() {
        var ring = new HashRing(List.of(), 128);

        assertTrue(ring.listaDePreferencia("x", 3).isEmpty());
    }
}
