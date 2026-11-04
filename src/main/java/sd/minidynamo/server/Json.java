package sd.minidynamo.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Serializacao explicita, sem depender do mapeamento automatico do Javalin.
 * Deixa visivel no codigo exatamente o que vai no fio, que e o que se quer
 * poder mostrar no seminario.
 */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    public static String escrever(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException("falha ao serializar " + o, e);
        }
    }

    public static <T> T ler(String json, Class<T> tipo) {
        try {
            return MAPPER.readValue(json, tipo);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON invalido: " + e.getMessage(), e);
        }
    }
}
