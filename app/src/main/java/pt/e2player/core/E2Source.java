package pt.e2player.core;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Enigma2: OpenWebif para canais/EPG e o proxy embutido (TsFixer) para os streams. */
public final class E2Source implements ChannelSource {
    private final OpenWebif owif;
    private final StreamProxy proxy;

    public E2Source(String box, int webPort, int streamPort, String user, String password, TsFixer.Log log) {
        owif = new OpenWebif(box, webPort, user, password);
        proxy = new StreamProxy(box, streamPort, user, password, log);
    }

    public void start() throws IOException {
        proxy.start();
    }

    public void stop() {
        proxy.stop();
    }

    public List<OpenWebif.Bouquet> bouquets(boolean radio) throws IOException {
        return owif.bouquets(radio);
    }

    public Map<String, List<Event>> epgNowNext(String bref) throws IOException {
        return owif.epgNowNext(bref);
    }

    public List<Event> epgService(String ref) throws IOException {
        return owif.epgService(ref);
    }

    public byte[] picon(String ref) {
        return owif.picon(ref);
    }

    public String streamUrl(String ref) {
        return proxy.url(ref);
    }

    public boolean sortAllByNumber() {
        return false;
    }
}
