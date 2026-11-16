package sd.minidynamo.server;

import sd.minidynamo.core.VersionVector;
import sd.minidynamo.core.Versioned;

import java.util.List;
import java.util.Map;

/**
 * Formatos que trafegam no fio. Ficam separados das classes de dominio de
 * proposito: o que vai no JSON e contrato entre nos e pode evoluir sem
 * arrastar o nucleo.
 */
public final class Dtos {

    private Dtos() {
    }

    /** Uma versao como aparece no JSON. relogio e o vetor serializado. */
    public record VersaoDto(String valor, Map<String, Long> relogio, long gravadoEm, boolean tombstone) {

        public static VersaoDto de(Versioned v) {
            return new VersaoDto(v.valor(), v.relogio().comoMapa(), v.gravadoEm(), v.tombstone());
        }

        public Versioned paraDominio() {
            return new Versioned(valor, new VersionVector(relogio), gravadoEm, tombstone);
        }
    }

    /** Corpo do PUT /kv/{chave} vindo do cliente. */
    public record PedidoEscrita(String valor, Map<String, Long> contexto) {
    }

    /** Corpo do PUT /internal/kv/{chave}: replicacao entre nos. */
    public record PedidoReplicacao(VersaoDto versao) {
    }

    /** Resposta do GET /internal/kv/{chave}. */
    public record RespostaInterna(String no, List<VersaoDto> versoes) {
    }

    /**
     * Resposta do GET /internal/digest: para cada chave, o vetor que resume o
     * que esta replica conhece. E o que a anti-entropia compara para decidir o
     * que empurrar e o que puxar, sem transferir os valores.
     */
    public record Digest(String no, Map<String, Map<String, Long>> relogios) {
    }

    /** Resposta do GET /kv/{chave} para o cliente. */
    public record RespostaLeitura(
            String chave,
            List<VersaoDto> valores,
            Map<String, Long> contexto,
            String coordenador,
            String modo,
            int r,
            int n,
            int replicasConsultadas,
            int replicasOk,
            List<String> replicasFalharam,
            boolean conflito,
            boolean causalSatisfeita,
            long duracaoMs
    ) {
    }

    /** Resposta do PUT /kv/{chave} para o cliente. */
    public record RespostaEscrita(
            String chave,
            Map<String, Long> contexto,
            String coordenador,
            String modo,
            int w,
            int n,
            int replicasOk,
            List<String> replicasFalharam,
            /** "node3 via node5": a replica estava fora e um substituto guardou a escrita. */
            List<String> hints,
            boolean sloppy,
            boolean quorumAtingido,
            long duracaoMs
    ) {
    }

    public record Erro(String erro, String detalhe) {
    }
}
