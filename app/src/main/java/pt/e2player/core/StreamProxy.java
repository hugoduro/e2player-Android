package pt.e2player.core;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Mini servidor HTTP local: GET /stream/&lt;ref&gt; vai buscar o stream à box
 * (porta 8001), passa-o pelo TsFixer e devolve-o ao player.
 */
public final class StreamProxy {
    private final String box;
    private final int streamPort;
    private final String auth;
    private final TsFixer.Log log;
    private ServerSocket server;
    private volatile boolean running;

    public StreamProxy(String box, int streamPort, String user, String password, TsFixer.Log log) {
        this.box = box;
        this.streamPort = streamPort;
        this.log = log == null ? TsFixer.STDERR : log;
        this.auth = OpenWebif.basicAuth(user, password);
    }

    /** Arranca numa porta livre de 127.0.0.1 e devolve a porta. */
    public synchronized int start() throws IOException {
        if (server != null) return server.getLocalPort();
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        running = true;
        Thread t = new Thread(this::acceptLoop, "e2proxy-accept");
        t.setDaemon(true);
        t.start();
        log.i("proxy em 127.0.0.1:" + server.getLocalPort());
        return server.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    public String url(String ref) {
        try {
            String q = URLEncoder.encode(ref, "UTF-8").replace("%3A", ":").replace("+", "%20");
            return "http://127.0.0.1:" + server.getLocalPort() + "/stream/" + q;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "e2proxy-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) log.w("accept: " + e);
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c, state = 0;
        while ((c = in.read()) >= 0 && sb.length() < 8192) {
            sb.append((char) c);
            if (c == '\r' && (state == 0 || state == 2)) state++;
            else if (c == '\n' && (state == 1 || state == 3)) state++;
            else state = 0;
            if (state == 4) break;
        }
        return sb.toString();
    }

    private void handle(Socket s) {
        HttpURLConnection up = null;
        String name = "?";
        try {
            s.setTcpNoDelay(true);
            String head = readHead(s.getInputStream());
            String[] first = head.split("\r\n", 2)[0].split(" ");
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            if (first.length < 2 || !first[1].startsWith("/stream/")) {
                out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            String ref = URLDecoder.decode(first[1].substring("/stream/".length()).replace("+", "%2B"), "UTF-8");
            name = ref.length() > 30 ? ref.substring(0, 30) : ref;
            String path = URLEncoder.encode(ref, "UTF-8").replace("%3A", ":").replace("+", "%20");
            URL url = new URL("http://" + box + ":" + streamPort + "/" + path);
            up = (HttpURLConnection) url.openConnection();
            up.setConnectTimeout(10000);
            up.setReadTimeout(20000);
            if (auth != null) up.setRequestProperty("Authorization", auth);
            int code = up.getResponseCode();
            if (code != 200) {
                log.w("[" + name + "] box respondeu HTTP " + code);
                out.write(("HTTP/1.0 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            out.write(("HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\n"
                    + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            TsFixer fixer = new TsFixer(TsFixer.serviceIdFromRef(ref), name, log);
            InputStream in = up.getInputStream();
            byte[] buf = new byte[188 * 350];
            int n;
            long sent = 0;
            while ((n = in.read(buf)) > 0) {
                fixer.feed(buf, 0, n, out);
                out.flush();
                sent += n;
            }
            log.i("[" + name + "] a box fechou o stream (" + sent / 1000 + " kB)");
        } catch (IOException e) {
            log.i("[" + name + "] terminado: " + e.getClass().getSimpleName());
        } finally {
            if (up != null) up.disconnect();
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
