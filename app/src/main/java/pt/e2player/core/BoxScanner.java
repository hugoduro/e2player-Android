package pt.e2player.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Procura boxes Enigma2 com OpenWebif na rede local (varrimento da sub-rede). */
public final class BoxScanner {

    public static final class Box {
        public final String ip, info;
        public final boolean needsAuth;

        Box(String ip, String info, boolean needsAuth) {
            this.ip = ip;
            this.info = info;
            this.needsAuth = needsAuth;
        }

        public String label() {
            return ip + "  —  " + info + (needsAuth ? "  (requer palavra-passe)" : "");
        }
    }

    public interface Listener {
        void progress(int done, int total);
    }

    /** Endereços IPv4 das redes locais (no máximo um /24 por interface, à volta do nosso IP). */
    public static List<String> localHosts() {
        Set<String> out = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    byte[] b = a.getAddress();
                    int prefix = Math.max(24, ia.getNetworkPrefixLength());
                    int ip = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
                    int mask = prefix >= 32 ? -1 : ~((1 << (32 - prefix)) - 1);
                    int net = ip & mask;
                    int count = prefix >= 31 ? 0 : (1 << (32 - prefix)) - 2;
                    for (int i = 1; i <= count; i++) {
                        int h = net + i;
                        if (h == ip) continue;
                        out.add(((h >>> 24) & 0xFF) + "." + ((h >>> 16) & 0xFF) + "." + ((h >>> 8) & 0xFF) + "." + (h & 0xFF));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(out);
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    /** Testa um IP: devolve a box se responder como OpenWebif, senão null. */
    public static Box probe(String ip, int port, int connectMs, int readMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + ip + ":" + port + "/web/about").openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code == 401) {
                String realm = String.valueOf(c.getHeaderField("WWW-Authenticate")).toLowerCase();
                String server = String.valueOf(c.getHeaderField("Server")).toLowerCase();
                if (realm.contains("openwebif") || realm.contains("enigma") || realm.contains("dreambox")
                        || server.contains("twisted"))
                    return new Box(ip, "OpenWebif", true);
                return null;
            }
            if (code != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && o.size() < 256 * 1024) o.write(buf, 0, n);
            String xml = new String(o.toByteArray(), StandardCharsets.UTF_8);
            if (!xml.contains("<e2about")) return null;
            List<String> parts = new ArrayList<>();
            String model = tag(xml, "e2model");
            if (model.isEmpty()) model = tag(xml, "e2boxtype");
            if (!model.isEmpty()) parts.add(model);
            String distro = tag(xml, "e2distroversion");
            String image = tag(xml, "e2imageversion");
            if (!distro.isEmpty() || !image.isEmpty()) parts.add((distro + " " + image).trim());
            if (parts.isEmpty()) {
                String webif = tag(xml, "e2webifversion");
                parts.add(webif.isEmpty() ? "Enigma2 / OpenWebif" : webif);
            }
            StringBuilder sb = new StringBuilder();
            for (String p : parts) sb.append(sb.length() > 0 ? " · " : "").append(p);
            return new Box(ip, sb.toString(), false);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Testa todos os hosts em paralelo. Pode ser cancelado. Resultado ordenado por IP. */
    public static List<Box> scan(List<String> hosts, final int port, int threads, final Listener l,
                                 final AtomicBoolean cancel) {
        final List<Box> found = Collections.synchronizedList(new ArrayList<Box>());
        final AtomicInteger done = new AtomicInteger();
        final int total = hosts.size();
        ExecutorService ex = Executors.newFixedThreadPool(Math.max(1, threads));
        for (final String h : hosts) {
            ex.execute(new Runnable() {
                public void run() {
                    if (cancel == null || !cancel.get()) {
                        Box b = probe(h, port, 700, 3000);
                        if (b != null) found.add(b);
                    }
                    int d = done.incrementAndGet();
                    if (l != null) l.progress(d, total);
                }
            });
        }
        ex.shutdown();
        try {
            ex.awaitTermination(2, TimeUnit.MINUTES);
        } catch (InterruptedException ignored) {
        }
        List<Box> out = new ArrayList<>(found);
        Collections.sort(out, (a, b) -> Long.compare(ipValue(a.ip), ipValue(b.ip)));
        return out;
    }

    static long ipValue(String ip) {
        long v = 0;
        for (String p : ip.split("\\.")) v = v * 256 + Integer.parseInt(p);
        return v;
    }
}
