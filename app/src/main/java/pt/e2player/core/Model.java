package pt.e2player.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canais, grupos, favoritos e EPG agora/seguinte (sem dependências Android). */
public final class Model {

    public static final class Channel {
        public final int num;
        public final String name, ref, key;
        public final Group group;

        Channel(int num, String name, String ref, Group group) {
            this.num = num;
            this.name = name;
            this.ref = ref;
            this.group = group;
            this.key = normref(ref);
        }
    }

    public static final class Group {
        public static final int BOUQUET = 0, FAV = 1, ALL = 2, SETTINGS = 3;
        public final String name, bref;
        public final int kind;
        public final List<Channel> channels;

        Group(String name, String bref, List<Channel> channels, int kind) {
            this.name = name;
            this.bref = bref;
            this.channels = channels;
            this.kind = kind;
        }
    }

    public final List<Group> bouquets = new ArrayList<>();
    public final List<Group> groups = new ArrayList<>();
    public final Map<Integer, Channel> byNum = new HashMap<>();
    public final Map<String, Channel> byKey = new HashMap<>();
    public final List<String> favorites = new ArrayList<>();
    /** "Todos os canais" ordenado pelo número (TVHeadend) em vez da ordem dos grupos. */
    public boolean sortAllByNumber = false;
    private final Map<String, List<Event>> nownext = new HashMap<>();
    private final Map<String, Long> epgTime = new HashMap<>();

    public static String normref(String ref) {
        String[] p = (ref == null ? "" : ref.trim()).split(":");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(10, p.length); i++) {
            if (i > 0) b.append(':');
            String f = p[i].toUpperCase().replaceFirst("^0+", "");
            b.append(f.isEmpty() ? "0" : f);
        }
        return b.toString();
    }

    public void load(List<OpenWebif.Bouquet> list) {
        bouquets.clear();
        byNum.clear();
        byKey.clear();
        int num = 0;
        for (OpenWebif.Bouquet b : list) {
            Group g = new Group(b.name, b.ref, new ArrayList<Channel>(), Group.BOUQUET);
            for (String[] s : b.services) {
                int n;
                if (s.length > 2 && s[2] != null && !s[2].isEmpty()) {   // número dado pela fonte
                    n = Integer.parseInt(s[2]);
                } else {
                    n = ++num;
                }
                Channel ch = new Channel(n, s[1].isEmpty() ? s[0] : s[1], s[0], g);
                g.channels.add(ch);
                if (!byNum.containsKey(n)) byNum.put(n, ch);
                if (!byKey.containsKey(ch.key)) byKey.put(ch.key, ch);
            }
            bouquets.add(g);
        }
        rebuildGroups();
    }

    public void rebuildGroups() {
        groups.clear();
        List<Channel> favs = new ArrayList<>();
        for (String k : favorites) if (byKey.containsKey(k)) favs.add(byKey.get(k));
        if (!favs.isEmpty()) groups.add(new Group("★ Favoritos", null, favs, Group.FAV));
        groups.addAll(bouquets);
        List<Channel> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Group g : bouquets)
            for (Channel ch : g.channels)
                if (seen.add(ch.key)) all.add(byKey.containsKey(ch.key) ? byKey.get(ch.key) : ch);
        if (sortAllByNumber) {
            java.util.Collections.sort(all, (a, b) -> a.num != b.num ? Integer.compare(a.num, b.num)
                    : a.name.compareToIgnoreCase(b.name));
        }
        if (!all.isEmpty()) groups.add(new Group("Todos os canais", null, all, Group.ALL));
        groups.add(new Group("⚙ Definições", null, new ArrayList<Channel>(), Group.SETTINGS));
    }

    public boolean isFav(Channel ch) {
        return ch != null && favorites.contains(ch.key);
    }

    public boolean toggleFav(Channel ch) {
        boolean fav;
        if (favorites.contains(ch.key)) {
            favorites.remove(ch.key);
            fav = false;
        } else {
            favorites.add(ch.key);
            fav = true;
        }
        rebuildGroups();
        return fav;
    }

    public synchronized void mergeEpg(String bref, Map<String, List<Event>> data) {
        nownext.putAll(data);
        epgTime.put(bref, System.currentTimeMillis());
    }

    public synchronized void markEpgTried(String bref) {
        epgTime.put(bref, System.currentTimeMillis());
    }

    /** {agora, seguinte}; qualquer um pode ser null. */
    public synchronized Event[] nowNext(Channel ch, long now) {
        Event cur = null, nxt = null;
        List<Event> l = nownext.get(ch.key);
        if (l != null) {
            for (Event e : l) {
                if (e.isLive(now)) cur = e;
                else if (e.start > now && nxt == null) nxt = e;
            }
        }
        return new Event[]{cur, nxt};
    }

    /** Bouquets cujo EPG já não tem evento atual para algum dos canais. */
    public synchronized Set<String> staleBrefs(List<Channel> chans, long now, long minAgeMs) {
        Set<String> out = new HashSet<>();
        long t = System.currentTimeMillis();
        for (Channel ch : chans) {
            String b = ch.group.bref;
            if (b == null || out.contains(b)) continue;
            Long last = epgTime.get(b);
            if (last != null && t - last < minAgeMs) continue;
            List<Event> l = nownext.get(ch.key);
            if (l != null && !l.isEmpty() && nowNext(ch, now)[0] == null) out.add(b);
        }
        return out;
    }

    public Channel neighbour(Group g, Channel ch, int step) {
        List<Channel> l = g.channels;
        if (l.isEmpty()) return null;
        int i = l.indexOf(ch);
        if (i < 0) return l.get(0);
        int n = l.size();
        return l.get(((i + step) % n + n) % n);
    }

    public int groupIndexFor(Channel ch, Group prefer) {
        if (prefer != null && groups.contains(prefer) && prefer.channels.contains(ch))
            return groups.indexOf(prefer);
        for (int i = 0; i < groups.size(); i++) if (groups.get(i) == ch.group) return i;
        return 0;
    }
}
