package pt.e2player.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fonte TVHeadend (API HTTP/JSON). Sem proxy: o player liga-se diretamente ao servidor.
 * Autenticação digest (por omissão no TVHeadend) ou basic; os streams usam um ticket.
 */
public final class TvhClient implements ChannelSource {
    public static final String ALL = "ALL";

    private final String host, user, password, profile, base;
    private final int port;
    private final Map<String, String> icons = new HashMap<>();
    private Map<String, String> challenge;   // último desafio digest recebido
    private int nc;

    public TvhClient(String host, int port, String user, String password, String profile) {
        this.host = host;
        this.port = port;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
        this.profile = profile == null || profile.trim().isEmpty() ? "pass" : profile.trim();
        this.base = "http://" + host + ":" + port;
    }

    public void start() {
    }

    public void stop() {
    }

    public boolean sortAllByNumber() {
        return true;
    }

    // ------------------------------------------------------------------ HTTP + digest
    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x & 0xFF));
            return b.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Map<String, String> parseChallenge(String h) {
        Map<String, String> m = new HashMap<>();
        Matcher mt = Pattern.compile("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]+))").matcher(h);
        while (mt.find()) m.put(mt.group(1).toLowerCase(), mt.group(2) != null ? mt.group(2) : mt.group(3));
        return m;
    }

    private synchronized String authHeader(String method, String uri) {
        if (user.isEmpty() || challenge == null) return null;
        if ("basic".equals(challenge.get("_scheme")))
            return OpenWebif.basicAuth(user, password);
        String realm = challenge.get("realm"), nonce = challenge.get("nonce");
        String qop = challenge.get("qop");
        String ha1 = md5(user + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        StringBuilder h = new StringBuilder("Digest username=\"").append(user)
                .append("\", realm=\"").append(realm).append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(uri).append('"');
        if (qop != null && qop.contains("auth")) {
            String ncs = String.format("%08x", ++nc);
            byte[] r = new byte[8];
            new SecureRandom().nextBytes(r);
            StringBuilder cn = new StringBuilder();
            for (byte x : r) cn.append(String.format("%02x", x & 0xFF));
            String resp = md5(ha1 + ":" + nonce + ":" + ncs + ":" + cn + ":auth:" + ha2);
            h.append(", qop=auth, nc=").append(ncs).append(", cnonce=\"").append(cn)
                    .append("\", response=\"").append(resp).append('"');
        } else {
            h.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append('"');
        }
        if (challenge.get("opaque") != null) h.append(", opaque=\"").append(challenge.get("opaque")).append('"');
        h.append(", algorithm=MD5");
        return h.toString();
    }

    private synchronized void learnChallenge(HttpURLConnection c) {
        List<String> hs = c.getHeaderFields().get("WWW-Authenticate");
        if (hs == null) {
            String one = c.getHeaderField("WWW-Authenticate");
            hs = one == null ? Collections.<String>emptyList() : Collections.singletonList(one);
        }
        Map<String, String> basic = null;
        for (String h : hs) {
            if (h == null) continue;
            String t = h.trim();
            if (t.regionMatches(true, 0, "Digest", 0, 6)) {
                challenge = parseChallenge(t.substring(6));
                challenge.put("_scheme", "digest");
                nc = 0;
                return;
            }
            if (t.regionMatches(true, 0, "Basic", 0, 5)) {
                basic = parseChallenge(t.substring(5));
                basic.put("_scheme", "basic");
            }
        }
        if (basic != null) challenge = basic;
    }

    /** GET de base+pathQuery com autenticação (1 nova tentativa após o desafio 401). */
    public byte[] get(String pathQuery, int timeoutMs) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(base + pathQuery).openConnection();
            c.setConnectTimeout(Math.min(timeoutMs, 10000));
            c.setReadTimeout(timeoutMs);
            String auth = authHeader("GET", pathQuery);
            if (auth != null) c.setRequestProperty("Authorization", auth);
            try {
                int code = c.getResponseCode();
                if (code == 401 && !user.isEmpty() && attempt == 0) {
                    learnChallenge(c);
                    continue;
                }
                if (code == 401) throw new IOException("TVHeadend recusou o utilizador/palavra-passe (401)");
                if (code != 200) throw new IOException("HTTP " + code + " em " + pathQuery);
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
        throw new IOException("autenticação falhou");
    }

    static String q(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> api(String path, Map<String, String> params, int timeoutMs) throws IOException {
        StringBuilder pq = new StringBuilder(path);
        char sep = '?';
        for (Map.Entry<String, String> e : params.entrySet()) {
            pq.append(sep).append(q(e.getKey())).append('=').append(q(e.getValue()));
            sep = '&';
        }
        String txt = new String(get(pq.toString(), timeoutMs), StandardCharsets.UTF_8);
        try {
            return Json.obj(Json.parse(txt));
        } catch (RuntimeException e) {
            throw new IOException("resposta inválida de " + path + ": " + e.getMessage());
        }
    }

    private static Map<String, String> p(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------ canais
    public List<OpenWebif.Bouquet> bouquets(boolean radio) throws IOException {
        List<Object> tags = Json.list(api("/api/channeltag/grid", p("limit", "10000"), 30000).get("entries"));
        List<Object> raw = Json.list(api("/api/channel/grid", p("limit", "100000"), 60000).get("entries"));
        final List<Map<String, Object>> chans = new ArrayList<>();
        for (Object o : raw) {
            Map<String, Object> c = Json.obj(o);
            if (Json.bool(c, "enabled", true) && !Json.str(c, "uuid").isEmpty()) chans.add(c);
        }
        // números: os do TVHeadend; os canais sem número vão para o fim
        int max = 0;
        for (Map<String, Object> c : chans) max = Math.max(max, (int) Json.dbl(c, "number", 0));
        Collections.sort(chans, (a, b) -> {
            int na = (int) Json.dbl(a, "number", 0), nb = (int) Json.dbl(b, "number", 0);
            if ((na <= 0) != (nb <= 0)) return na <= 0 ? 1 : -1;
            if (na != nb) return Integer.compare(na, nb);
            return Json.str(a, "name").compareToIgnoreCase(Json.str(b, "name"));
        });
        final Map<String, Integer> nums = new HashMap<>();
        for (Map<String, Object> c : chans) {
            int n = (int) Json.dbl(c, "number", 0);
            if (n <= 0) n = ++max;
            String uuid = Json.str(c, "uuid");
            nums.put(uuid, n);
            String icon = Json.str(c, "icon_public_url");
            if (icon.isEmpty()) icon = Json.str(c, "icon");
            if (!icon.isEmpty()) icons.put(Model.normref(uuid), icon);
        }

        List<Map<String, Object>> tl = new ArrayList<>();
        for (Object o : tags) {
            Map<String, Object> t = Json.obj(o);
            if (Json.bool(t, "enabled", true) && !Json.bool(t, "internal", false)) tl.add(t);
        }
        Collections.sort(tl, (a, b) -> {
            int c = Double.compare(Json.dbl(a, "index", 0), Json.dbl(b, "index", 0));
            return c != 0 ? c : Json.str(a, "name").compareToIgnoreCase(Json.str(b, "name"));
        });
        List<OpenWebif.Bouquet> out = new ArrayList<>();
        for (Map<String, Object> t : tl) {
            String tid = Json.str(t, "uuid");
            List<String[]> members = new ArrayList<>();
            for (Map<String, Object> c : chans) {
                if (Json.list(c.get("tags")).contains(tid)) members.add(entry(c, nums));
            }
            if (!members.isEmpty()) out.add(new OpenWebif.Bouquet(OpenWebif.clean(Json.str(t, "name")), ALL, members));
        }
        if (out.isEmpty()) {
            List<String[]> all = new ArrayList<>();
            for (Map<String, Object> c : chans) all.add(entry(c, nums));
            out.add(new OpenWebif.Bouquet("Canais", ALL, all));
        }
        return out;
    }

    private static String[] entry(Map<String, Object> c, Map<String, Integer> nums) {
        String uuid = Json.str(c, "uuid");
        return new String[]{uuid, OpenWebif.clean(Json.str(c, "name")), String.valueOf(nums.get(uuid))};
    }

    // ------------------------------------------------------------------ EPG
    private static Event event(Map<String, Object> e) {
        List<String> parts = new ArrayList<>();
        for (String k : new String[]{"subtitle", "summary", "description"}) {
            String v = OpenWebif.clean(Json.str(e, k));
            if (!v.isEmpty() && !parts.contains(v)) parts.add(v);
        }
        StringBuilder d = new StringBuilder();
        for (String s : parts) d.append(d.length() > 0 ? "\n" : "").append(s);
        long start = (long) Json.dbl(e, "start", 0), stop = (long) Json.dbl(e, "stop", 0);
        return new Event(Json.str(e, "channelUuid"), OpenWebif.clean(Json.str(e, "title")),
                start, Math.max(0, stop - start), d.toString());
    }

    public Map<String, List<Event>> epgNowNext(String bref) throws IOException {
        List<Object> now = Json.list(api("/api/epg/events/grid", p("mode", "now", "limit", "100000"), 60000)
                .get("entries"));
        List<Event> evs = new ArrayList<>();
        List<String> nextIds = new ArrayList<>();
        for (Object o : now) {
            Map<String, Object> e = Json.obj(o);
            evs.add(event(e));
            String nid = Json.str(e, "nextEventId");
            if (!nid.isEmpty() && !nid.equals("0")) nextIds.add(nid);
        }
        for (int i = 0; i < nextIds.size(); i += 200) {   // o "seguinte" é opcional
            StringBuilder arr = new StringBuilder("[");
            for (int k = i; k < Math.min(nextIds.size(), i + 200); k++)
                arr.append(k > i ? "," : "").append(nextIds.get(k));
            arr.append(']');
            try {
                for (Object o : Json.list(api("/api/epg/events/load", p("eventId", arr.toString()), 60000)
                        .get("entries"))) evs.add(event(Json.obj(o)));
            } catch (IOException e) {
                break;
            }
        }
        Map<String, List<Event>> out = new HashMap<>();
        for (Event ev : evs) {
            if (ev.start <= 0) continue;
            String k = Model.normref(ev.ref);
            List<Event> l = out.get(k);
            if (l == null) out.put(k, l = new ArrayList<>());
            l.add(ev);
        }
        for (List<Event> l : out.values()) Collections.sort(l, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    public List<Event> epgService(String ref) throws IOException {
        List<Event> out = new ArrayList<>();
        for (Object o : Json.list(api("/api/epg/events/grid", p("channel", ref, "limit", "500"), 60000)
                .get("entries"))) {
            Event e = event(Json.obj(o));
            if (e.start > 0) out.add(e);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    // ------------------------------------------------------------------ ícones
    public byte[] picon(String ref) {
        String url = icons.get(Model.normref(ref));
        if (url == null) return null;
        try {
            byte[] d;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                if (url.startsWith(base)) {
                    d = get(url.substring(base.length()), 10000);
                } else {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(10000);
                    try (InputStream in = c.getInputStream()) {
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        byte[] b = new byte[16384];
                        int n;
                        while ((n = in.read(b)) > 0) o.write(b, 0, n);
                        d = o.toByteArray();
                    } finally {
                        c.disconnect();
                    }
                }
            } else {
                d = get("/" + url.replaceFirst("^/+", ""), 10000);
            }
            return d.length > 0 ? d : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ stream
    private String withProfile(String url) {
        return url + (url.contains("?") ? "&" : "?") + "profile=" + q(profile);
    }

    public String streamUrl(String ref) throws IOException {
        String path = "/stream/channel/" + ref;
        if (user.isEmpty()) return withProfile(base + path);
        // com autenticação: playlist com ticket (válido 5 min para iniciar o stream)
        String m3u = new String(get("/playlist/ticket/channel/" + ref, 10000), StandardCharsets.UTF_8);
        for (String line : m3u.split("\r?\n")) {
            line = line.trim();
            if (line.startsWith("http") && line.contains("ticket=")) {
                URL u = new URL(line);
                // usa sempre o nosso endereço (o TVHeadend pode devolver outro nome de host)
                return withProfile(base + u.getPath() + "?" + u.getQuery());
            }
        }
        throw new IOException("o TVHeadend não devolveu um ticket para o canal");
    }
}
