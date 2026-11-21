package pt.e2player.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/** Cliente da API XML do OpenWebif: bouquets, EPG e picons. */
public final class OpenWebif {
    public static final String TV_ROOT =
            "1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"bouquets.tv\" ORDER BY bouquet";
    public static final String RADIO_ROOT =
            "1:7:2:0:0:0:0:0:0:0:FROM BOUQUET \"bouquets.radio\" ORDER BY bouquet";

    private final String base;
    private final String auth;

    public OpenWebif(String box, int webPort, String user, String password) {
        this.base = "http://" + box + ":" + webPort;
        this.auth = basicAuth(user, password);
    }

    public static String basicAuth(String user, String password) {
        if (user == null || user.isEmpty()) return null;
        String tok = user + ":" + (password == null ? "" : password);
        return "Basic " + Base64Codec.encode(tok.getBytes(StandardCharsets.UTF_8));
    }

    public static final class Bouquet {
        public final String name, ref;
        public final List<String[]> services; // {ref, nome}

        Bouquet(String name, String ref, List<String[]> services) {
            this.name = name;
            this.ref = ref;
            this.services = services;
        }
    }

    // ------------------------------------------------------------------ HTTP
    public byte[] get(String path, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(Math.min(timeoutMs, 10000));
        c.setReadTimeout(timeoutMs);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " em " + path);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static String q(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || (c >= 0x20 && !(c >= 0x7F && c <= 0x9F))) b.append(c);
        }
        return b.toString().trim();
    }

    private Element xml(String path, int timeoutMs) throws IOException {
        String raw = new String(get(path, timeoutMs), StandardCharsets.UTF_8);
        raw = clean(raw);
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            Document d = f.newDocumentBuilder().parse(
                    new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
            return d.getDocumentElement();
        } catch (Exception e) {
            throw new IOException("XML inválido em " + path + ": " + e.getMessage());
        }
    }

    private static String text(Element parent, String tag) {
        NodeList l = parent.getElementsByTagName(tag);
        if (l.getLength() == 0) return "";
        String t = l.item(0).getTextContent();
        return t == null ? "" : t.trim();
    }

    // ------------------------------------------------------------------ canais
    public List<String[]> services(String sref) throws IOException {
        Element root = xml("/web/getservices?sRef=" + q(sref), 30000);
        List<String[]> out = new ArrayList<>();
        NodeList l = root.getElementsByTagName("e2service");
        for (int i = 0; i < l.getLength(); i++) {
            Element e = (Element) l.item(i);
            out.add(new String[]{text(e, "e2servicereference"), clean(text(e, "e2servicename"))});
        }
        return out;
    }

    public static boolean isMarker(String ref) {
        String[] p = ref.split(":");
        return p.length < 10 || p[1].equals("64") || p[1].equals("320") || p[1].equals("832");
    }

    public List<Bouquet> bouquets(boolean radio) throws IOException {
        List<Bouquet> out = new ArrayList<>();
        List<String> roots = new ArrayList<>();
        roots.add(TV_ROOT);
        if (radio) roots.add(RADIO_ROOT);
        IOException last = null;
        for (String root : roots) {
            for (String[] b : services(root)) {
                try {
                    List<String[]> chans = new ArrayList<>();
                    for (String[] s : services(b[0])) if (!isMarker(s[0])) chans.add(s);
                    if (!chans.isEmpty()) out.add(new Bouquet(b[1], b[0], chans));
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        if (out.isEmpty()) throw last != null ? last : new IOException("a box não devolveu bouquets");
        return out;
    }

    // ------------------------------------------------------------------ EPG
    private static List<Event> events(Element root) {
        List<Event> out = new ArrayList<>();
        NodeList l = root.getElementsByTagName("e2event");
        for (int i = 0; i < l.getLength(); i++) {
            Element e = (Element) l.item(i);
            long start, dur;
            try {
                start = (long) Double.parseDouble(text(e, "e2eventstart"));
                dur = (long) Double.parseDouble(text(e, "e2eventduration"));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (start <= 0) continue;
            String desc = clean(text(e, "e2eventdescription"));
            String ext = clean(text(e, "e2eventdescriptionextended"));
            if (!ext.isEmpty() && !ext.equals(desc)) desc = (desc + "\n" + ext).trim();
            out.add(new Event(text(e, "e2eventservicereference"), clean(text(e, "e2eventtitle")),
                    start, dur, desc));
        }
        return out;
    }

    /** normref -> eventos (agora e seguinte) de um bouquet. Falha se os dois pedidos falharem. */
    public Map<String, List<Event>> epgNowNext(String bref) throws IOException {
        Map<String, List<Event>> out = new HashMap<>();
        List<String> errors = new ArrayList<>();
        for (String p : new String[]{"/web/epgnow?bRef=", "/web/epgnext?bRef="}) {
            try {
                for (Event ev : events(xml(p + q(bref), 45000))) {
                    String k = Model.normref(ev.ref);
                    List<Event> l = out.get(k);
                    if (l == null) {
                        l = new ArrayList<>();
                        out.put(k, l);
                    }
                    l.add(ev);
                }
            } catch (IOException e) {
                errors.add(p.substring(0, p.indexOf('?')) + ": " + e.getMessage());
            }
        }
        if (errors.size() == 2) throw new IOException(errors.get(0) + "; " + errors.get(1));
        for (List<Event> l : out.values()) Collections.sort(l, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    public List<Event> epgService(String sref) throws IOException {
        List<Event> l = events(xml("/web/epgservice?sRef=" + q(sref), 30000));
        Collections.sort(l, (a, b) -> Long.compare(a.start, b.start));
        return l;
    }

    public static String piconName(String ref) {
        String[] p = ref.trim().split(":");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(10, p.length); i++) {
            if (i > 0) b.append('_');
            b.append(p[i].toUpperCase());
        }
        return b + ".png";
    }

    public byte[] picon(String ref) {
        try {
            byte[] d = get("/picon/" + piconName(ref), 10000);
            if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return d;
        } catch (IOException ignored) {
        }
        return null;
    }

    /** Base64 sem depender de java.util.Base64 (API 26 no Android). */
    static final class Base64Codec {
        private static final char[] A =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

        static String encode(byte[] d) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < d.length; i += 3) {
                int b = (d[i] & 0xFF) << 16;
                if (i + 1 < d.length) b |= (d[i + 1] & 0xFF) << 8;
                if (i + 2 < d.length) b |= d[i + 2] & 0xFF;
                s.append(A[(b >> 18) & 63]).append(A[(b >> 12) & 63]);
                s.append(i + 1 < d.length ? A[(b >> 6) & 63] : '=');
                s.append(i + 2 < d.length ? A[b & 63] : '=');
            }
            return s.toString();
        }
    }
}
