package pt.e2player.core;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Origem dos canais/EPG/streams (Enigma2 ou TVHeadend). A interface é a mesma. */
public interface ChannelSource {
    /** Arranca o que for preciso (ex.: o proxy do Enigma2). */
    void start() throws IOException;

    void stop();

    /** Grupos; cada serviço é {ref, nome} ou {ref, nome, número}. */
    List<OpenWebif.Bouquet> bouquets(boolean radio) throws IOException;

    /** normref -> eventos agora/seguinte do grupo (bref). */
    Map<String, List<Event>> epgNowNext(String bref) throws IOException;

    List<Event> epgService(String ref) throws IOException;

    byte[] picon(String ref);

    /** URL a dar ao player (pode fazer pedidos à rede: chamar fora da thread de UI). */
    String streamUrl(String ref) throws IOException;

    /** "Todos os canais" ordenado pelo número (TVHeadend) ou pela ordem dos bouquets (Enigma2). */
    boolean sortAllByNumber();
}
