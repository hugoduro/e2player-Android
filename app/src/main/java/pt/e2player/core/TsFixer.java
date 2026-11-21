package pt.e2player.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Corrige a PAT/PMT de um MPEG-TS vindo de uma box Enigma2 (porta 8001).
 * Porte do TSFixer do e2proxy.py:
 *  - gera PAT/PMT novas só com o serviço pedido e os PIDs que existem;
 *  - remove descritores CA, AIT/DSM-CC, ECM/EMM;
 *  - corrige o PCR_PID; repete PAT/PMT a cada ~100 ms;
 *  - descarta pacotes ainda encriptados; arranca o vídeo num keyframe;
 *  - sem PMT, deduz as streams pelos cabeçalhos PES.
 */
public final class TsFixer {

    public interface Log {
        void i(String msg);

        void w(String msg);
    }

    public static final Log STDERR = new Log() {
        public void i(String m) { System.err.println("I " + m); }

        public void w(String m) { System.err.println("W " + m); }
    };

    static final int TS = 188;
    static final int SYNC = 0x47;
    static final int NULL_PID = 0x1FFF;

    static final int DESC_CA = 0x09, DESC_AC3 = 0x6A, DESC_EAC3 = 0x7A, DESC_AAC = 0x7C,
            DESC_EXT = 0x7F, DESC_TELETEXT = 0x56, DESC_SUBTITLE = 0x59;

    static final Set<Integer> VIDEO_TYPES = set(0x01, 0x02, 0x10, 0x1B, 0x24, 0x33, 0x42, 0xEA);
    static final Set<Integer> AUDIO_TYPES = set(0x03, 0x04, 0x0F, 0x11, 0x1C, 0x81, 0x87);
    static final Set<Integer> DATA_TYPES = set(0x05, 0x0A, 0x0B, 0x0C, 0x0D);

    static Set<Integer> set(int... v) {
        Set<Integer> s = new HashSet<>();
        for (int x : v) s.add(x);
        return s;
    }

    static int u(byte[] a, int i) {
        return a[i] & 0xFF;
    }

    // ------------------------------------------------------------------ CRC
    private static final int[] CRC = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i << 24;
            for (int k = 0; k < 8; k++) c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04C11DB7 : c << 1;
            CRC[i] = c;
        }
    }

    public static int crc32(byte[] d, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) crc = (crc << 8) ^ CRC[((crc >>> 24) ^ d[i]) & 0xFF];
        return crc;
    }

    // ------------------------------------------------------------------ ES
    public static final class Es {
        final int stype, pid;
        final byte[] desc;

        Es(int stype, int pid, byte[] desc) {
            this.stype = stype;
            this.pid = pid;
            this.desc = desc == null ? new byte[0] : desc;
        }

        Set<Integer> tags() {
            Set<Integer> s = new HashSet<>();
            for (byte[] d : descriptors(desc)) s.add(d[0] & 0xFF);
            return s;
        }

        String kind() {
            Set<Integer> t = tags();
            if (VIDEO_TYPES.contains(stype)) return "video";
            if (AUDIO_TYPES.contains(stype) || t.contains(DESC_AC3) || t.contains(DESC_EAC3)
                    || t.contains(DESC_AAC) || t.contains(DESC_EXT)) return "audio";
            if (t.contains(DESC_SUBTITLE)) return "legendas";
            if (t.contains(DESC_TELETEXT)) return "teletexto";
            if (DATA_TYPES.contains(stype)) return "dados";
            return "outro";
        }

        String describe() {
            return String.format("PID 0x%04X tipo 0x%02X (%s)", pid, stype, kind());
        }
    }

    static List<byte[]> descriptors(byte[] buf) {
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        while (i + 2 <= buf.length) {
            int ln = buf[i + 1] & 0xFF;
            if (i + 2 + ln > buf.length) break;
            byte[] d = new byte[2 + ln];
            System.arraycopy(buf, i, d, 0, d.length);
            out.add(d);
            i += 2 + ln;
        }
        return out;
    }

    static byte[] stripCa(byte[] desc) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] d : descriptors(desc)) if ((d[0] & 0xFF) != DESC_CA) o.write(d, 0, d.length);
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ secções
    static final class SectionAssembler {
        ByteArrayOutputStream buf;

        List<byte[]> feed(byte[] pkt, int payloadOff) {
            List<byte[]> out = new ArrayList<>();
            if (payloadOff >= TS) return out;
            boolean pusi = (pkt[1] & 0x40) != 0;
            if (pusi) {
                int ptr = u(pkt, payloadOff);
                int start = payloadOff + 1;
                if (buf != null) {
                    int n = Math.min(ptr, TS - start);
                    buf.write(pkt, start, n);
                    drain(out);
                }
                buf = new ByteArrayOutputStream();
                int s2 = start + ptr;
                if (s2 < TS) buf.write(pkt, s2, TS - s2);
            } else {
                if (buf == null) return out;
                buf.write(pkt, payloadOff, TS - payloadOff);
            }
            drain(out);
            return out;
        }

        private void drain(List<byte[]> out) {
            while (buf != null) {
                byte[] b = buf.toByteArray();
                if (b.length < 3) return;
                if ((b[0] & 0xFF) == 0xFF) {
                    buf = null;
                    return;
                }
                int ln = (((b[1] & 0x0F) << 8) | (b[2] & 0xFF)) + 3;
                if (b.length < ln) return;
                byte[] sec = new byte[ln];
                System.arraycopy(b, 0, sec, 0, ln);
                buf = new ByteArrayOutputStream();
                buf.write(b, ln, b.length - ln);
                if (ln >= 12 && crc32(sec, 0, ln) == 0) out.add(sec);
            }
        }
    }

    static final class Pmt {
        int prog, version, pcrPid;
        byte[] progDesc;
        List<Es> es = new ArrayList<>();
        byte[] raw;
    }

    static Pmt parsePmt(byte[] s) {
        if (u(s, 0) != 0x02) return null;
        Pmt p = new Pmt();
        p.raw = s;
        p.prog = (u(s, 3) << 8) | u(s, 4);
        p.version = (u(s, 5) >> 1) & 0x1F;
        p.pcrPid = ((u(s, 8) & 0x1F) << 8) | u(s, 9);
        int pil = ((u(s, 10) & 0x0F) << 8) | u(s, 11);
        p.progDesc = slice(s, 12, Math.min(12 + pil, s.length - 4));
        int i = 12 + pil, end = s.length - 4;
        while (i + 5 <= end) {
            int st = u(s, i);
            int pid = ((u(s, i + 1) & 0x1F) << 8) | u(s, i + 2);
            int eil = ((u(s, i + 3) & 0x0F) << 8) | u(s, i + 4);
            p.es.add(new Es(st, pid, slice(s, i + 5, Math.min(i + 5 + eil, end))));
            i += 5 + eil;
        }
        return p;
    }

    static byte[] slice(byte[] a, int from, int to) {
        if (to < from) to = from;
        byte[] r = new byte[to - from];
        System.arraycopy(a, from, r, 0, r.length);
        return r;
    }

    static byte[] makeSection(int tableId, byte[] body) {
        int length = body.length + 4;
        byte[] s = new byte[3 + length];
        s[0] = (byte) tableId;
        s[1] = (byte) (0xB0 | ((length >> 8) & 0x0F));
        s[2] = (byte) length;
        System.arraycopy(body, 0, s, 3, body.length);
        int crc = crc32(s, 0, 3 + body.length);
        int o = 3 + body.length;
        s[o] = (byte) (crc >>> 24);
        s[o + 1] = (byte) (crc >>> 16);
        s[o + 2] = (byte) (crc >>> 8);
        s[o + 3] = (byte) crc;
        return s;
    }

    static byte[] buildPat(int tsid, int prog, int pmtPid, int ver) {
        byte[] b = {(byte) (tsid >> 8), (byte) tsid, (byte) (0xC1 | (ver << 1)), 0, 0,
                (byte) (prog >> 8), (byte) prog, (byte) (0xE0 | (pmtPid >> 8)), (byte) pmtPid};
        return makeSection(0x00, b);
    }

    static byte[] buildPmt(int prog, int ver, int pcrPid, byte[] progDesc, List<Es> es) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(prog >> 8);
        b.write(prog);
        b.write(0xC1 | (ver << 1));
        b.write(0);
        b.write(0);
        b.write(0xE0 | (pcrPid >> 8));
        b.write(pcrPid);
        b.write(0xF0 | (progDesc.length >> 8));
        b.write(progDesc.length);
        b.write(progDesc, 0, progDesc.length);
        for (Es e : es) {
            b.write(e.stype);
            b.write(0xE0 | (e.pid >> 8));
            b.write(e.pid);
            b.write(0xF0 | (e.desc.length >> 8));
            b.write(e.desc.length);
            b.write(e.desc, 0, e.desc.length);
        }
        return makeSection(0x02, b.toByteArray());
    }

    // ------------------------------------------------------------------ PES
    static int indexOfStartCode(byte[] d, int from, int end) {
        for (int i = from; i + 2 < end; i++)
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) return i;
        return -1;
    }

    /** pkt[off..] começa um PES. */
    static Es guessFromPes(int pid, byte[] pkt, int off) {
        if (TS - off < 9 || pkt[off] != 0 || pkt[off + 1] != 0 || pkt[off + 2] != 1) return null;
        int sid = u(pkt, off + 3);
        int d = off + 9 + u(pkt, off + 8);
        if (d >= TS) return null;
        if (sid >= 0xE0 && sid <= 0xEF) {
            int k = indexOfStartCode(pkt, d, TS);
            if (k >= 0 && k + 3 < TS) {
                int b = u(pkt, k + 3);
                if (b == 0xB3 || b == 0x00 || b == 0xB5 || b == 0xB8) return new Es(0x02, pid, null);
                int ht = (b >> 1) & 0x3F;
                if (ht == 32 || ht == 33 || ht == 34 || ht == 35 || ht == 39) return new Es(0x24, pid, null);
            }
            return new Es(0x1B, pid, null);
        }
        if (sid >= 0xC0 && sid <= 0xDF) {
            if (d + 1 < TS && u(pkt, d) == 0xFF && (u(pkt, d + 1) & 0xF0) == 0xF0
                    && (u(pkt, d + 1) & 0x06) == 0) return new Es(0x0F, pid, null);
            return new Es(0x03, pid, null);
        }
        if (sid == 0xBD && d + 6 <= TS) {
            if (u(pkt, d) == 0x0B && u(pkt, d + 1) == 0x77) {
                int bsid = u(pkt, d + 5) >> 3;
                return new Es(0x06, pid, new byte[]{(byte) (bsid > 10 ? DESC_EAC3 : DESC_AC3), 1, 0});
            }
            if (u(pkt, d) == 0x20 && u(pkt, d + 1) == 0x00)
                return new Es(0x06, pid, new byte[]{DESC_SUBTITLE, 8, 'u', 'n', 'd', 0x10, 0, 1, 0, 1});
            if (u(pkt, d) >= 0x10 && u(pkt, d) <= 0x1F)
                return new Es(0x06, pid, new byte[]{DESC_TELETEXT, 5, 'u', 'n', 'd', 0x09, 0x00});
        }
        return null;
    }

    // ------------------------------------------------------------------ estado
    static final class PidInfo {
        int count, scrambled, clear;
        boolean hasPcr, pusiSeen;
        Es guess;
    }

    private final Log log;
    private final String name;
    private Integer serviceId;
    public double probeSecs = 1.0, pmtTimeout = 4.0, descrambleWait = 8.0;
    public double repeatSecs = 0.1;
    public int repeatPackets = 2000, maxProbePackets = 300000;
    public boolean keepData = false;

    private byte[] rest = new byte[0];
    private boolean probing = true;
    private final List<byte[]> probeBuf = new ArrayList<>();
    private long npackets = 0;

    private final Map<Integer, PidInfo> pids = new HashMap<>();
    private final Map<Integer, SectionAssembler> asm = new HashMap<>();
    private int tsid = 1;
    private Integer origPmtPid = null;
    private Pmt origPmt = null;

    private Double lastPcr = null;
    private long pcrRaw = -1;

    private int prog = 1, outPmtPid = 0x1000, outPcrPid = NULL_PID;
    private byte[] outProgDesc = new byte[0];
    private List<Es> outEs = new ArrayList<>();
    private final Set<Integer> allowed = new HashSet<>();
    private final Set<Integer> started = new HashSet<>();
    private int pmtVer = 0, ccPat = 0, ccPmt = 0, videoPesSkipped = 0;
    private byte[] patSec, pmtSec;
    private Double lastPsiTime = null;
    private int sincePsi = 0;
    private long scrDropped = 0;
    private Double scrLastLog = null;

    public TsFixer(Integer serviceId, String name, Log log) {
        this.serviceId = serviceId;
        this.name = name;
        this.log = log == null ? STDERR : log;
    }

    private double elapsed() {
        return lastPcr == null ? 0.0 : lastPcr;
    }

    private boolean updatePcr(byte[] p) {
        if ((p[3] & 0x20) != 0 && u(p, 4) >= 7 && (p[5] & 0x10) != 0) {
            long base = ((long) u(p, 6) << 25) | ((long) u(p, 7) << 17) | ((long) u(p, 8) << 9)
                    | ((long) u(p, 9) << 1) | (u(p, 10) >> 7);
            if (pcrRaw < 0) {
                lastPcr = 0.0;
            } else {
                long diff = (base - pcrRaw) & ((1L << 33) - 1);
                if (diff < 90000L * 5) lastPcr = lastPcr + diff / 90000.0;
            }
            pcrRaw = base;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ entrada
    public void feed(byte[] data, int off, int len, OutputStream os) throws IOException {
        byte[] buf;
        if (rest.length == 0) {
            buf = data;
        } else {
            buf = new byte[rest.length + len];
            System.arraycopy(rest, 0, buf, 0, rest.length);
            System.arraycopy(data, off, buf, rest.length, len);
            off = 0;
            len = buf.length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(len + 4096);
        int i = off, end = off + len;
        while (i + TS <= end) {
            if (u(buf, i) != SYNC) {
                int j = i + 1;
                while (j + TS < end && !(u(buf, j) == SYNC && u(buf, j + TS) == SYNC)) j++;
                if (j + TS >= end) {
                    i = j;
                    break;
                }
                i = j;
                continue;
            }
            byte[] pkt = new byte[TS];
            System.arraycopy(buf, i, pkt, 0, TS);
            i += TS;
            packet(pkt, out);
        }
        rest = slice(buf, Math.min(i, end), end);
        if (out.size() > 0) out.writeTo(os);
    }

    public void flush(OutputStream os) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (probing) finishProbe(out);
        out.writeTo(os);
    }

    private void packet(byte[] pkt, ByteArrayOutputStream out) {
        npackets++;
        int pid = ((pkt[1] & 0x1F) << 8) | u(pkt, 2);
        if (pid == NULL_PID) return;
        int afc = (u(pkt, 3) >> 4) & 3;
        int payloadOff = 4;
        if ((afc & 2) != 0) payloadOff += 1 + u(pkt, 4);
        boolean hasPayload = (afc & 1) != 0 && payloadOff < TS;
        int tsc = u(pkt, 3) & 0xC0;

        PidInfo info = pids.get(pid);
        if (info == null) {
            info = new PidInfo();
            pids.put(pid, info);
        }
        if (hasPayload) {
            info.count++;
            if (tsc != 0) info.scrambled++;
            else info.clear++;
        }
        if ((afc & 2) != 0 && updatePcr(pkt)) info.hasPcr = true;
        boolean pusi = (pkt[1] & 0x40) != 0;
        if (pusi && hasPayload && tsc == 0) {
            info.pusiSeen = true;
            if (info.guess == null && pid != 0 && (origPmtPid == null || pid != origPmtPid))
                info.guess = guessFromPes(pid, pkt, payloadOff);
        }

        if (pid == 0 && hasPayload) {
            for (byte[] sec : asmFor(pid).feed(pkt, payloadOff)) onPat(sec);
            if (!probing) return;
        } else if (origPmtPid != null && pid == origPmtPid && hasPayload) {
            for (byte[] sec : asmFor(pid).feed(pkt, payloadOff)) onPmt(sec, out);
            if (!probing) return;
        }

        if (probing) {
            if (tsc == 0 || (afc & 2) != 0) probeBuf.add(pkt);
            if (probeDone()) finishProbe(out);
            return;
        }
        if (!allowed.contains(pid)) {
            maybeLateEs(pid, info, out);
            if (!allowed.contains(pid)) return;
        }
        emit(pid, pkt, pusi, out);
    }

    private SectionAssembler asmFor(int pid) {
        SectionAssembler a = asm.get(pid);
        if (a == null) {
            a = new SectionAssembler();
            asm.put(pid, a);
        }
        return a;
    }

    private void onPat(byte[] s) {
        if (u(s, 0) != 0) return;
        tsid = (u(s, 3) << 8) | u(s, 4);
        Map<Integer, Integer> progs = new TreeMap<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 8; i + 4 <= s.length - 4; i += 4) {
            int pr = (u(s, i) << 8) | u(s, i + 1);
            int pid = ((u(s, i + 2) & 0x1F) << 8) | u(s, i + 3);
            if (pr != 0) {
                progs.put(pr, pid);
                order.add(pr);
            }
        }
        if (order.isEmpty()) return;
        int p;
        if (serviceId != null && progs.containsKey(serviceId)) {
            p = serviceId;
        } else {
            p = order.get(0);
            if (serviceId != null && origPmtPid == null)
                log.w(String.format("[%s] serviço 0x%X não está na PAT; uso 0x%X", name, serviceId, p));
            serviceId = p;
        }
        int pmtPid = progs.get(p);
        if (origPmtPid == null || pmtPid != origPmtPid) {
            origPmtPid = pmtPid;
            asm.remove(pmtPid);
        }
    }

    private void onPmt(byte[] sec, ByteArrayOutputStream out) {
        Pmt p = parsePmt(sec);
        if (p == null) return;
        if (serviceId != null && p.prog != serviceId) return;
        if (origPmt != null && java.util.Arrays.equals(origPmt.raw, p.raw)) return;
        boolean first = origPmt == null;
        origPmt = p;
        if (first) {
            StringBuilder sb = new StringBuilder();
            for (Es e : p.es) sb.append("\n   ").append(e.describe()).append(e.tags().contains(DESC_CA) ? " [CA]" : "");
            log.i(String.format("[%s] PMT original prog 0x%X PCR 0x%X:%s", name, p.prog, p.pcrPid, sb));
        } else if (!probing) {
            buildOutput(out, "PMT original mudou");
        }
    }

    private boolean wanted(Es e) {
        return keepData || !"dados".equals(e.kind());
    }

    private boolean probeDone() {
        double t = elapsed();
        if (npackets >= maxProbePackets) return true;
        if (origPmt == null) return t >= pmtTimeout;
        boolean any = false, all = true, scr = false;
        for (Es e : origPmt.es) {
            if (!wanted(e)) continue;
            String k = e.kind();
            if (!k.equals("video") && !k.equals("audio")) continue;
            any = true;
            PidInfo i = pids.get(e.pid);
            if (i == null || !i.pusiSeen) all = false;
            if (i != null && i.scrambled > 0) scr = true;
        }
        if (any && all && t >= 0.2) return true;
        if (scr) return t >= Math.max(probeSecs, descrambleWait);
        return t >= probeSecs;
    }

    private void finishProbe(ByteArrayOutputStream out) {
        probing = false;
        if (origPmt == null) log.w("[" + name + "] sem PMT - a deduzir streams pelos PES");
        buildOutput(null, "início");
        for (Es e : outEs) {
            PidInfo i = pids.get(e.pid);
            if (i != null && i.scrambled > 0 && i.clear == 0)
                log.w("[" + name + "] " + e.describe() + " chega ENCRIPTADO: a box não está a desencriptar");
        }
        emitPsi(out);
        for (byte[] pkt : probeBuf) {
            int pid = ((pkt[1] & 0x1F) << 8) | u(pkt, 2);
            if (allowed.contains(pid)) emit(pid, pkt, (pkt[1] & 0x40) != 0, out);
        }
        probeBuf.clear();
    }

    private void buildOutput(ByteArrayOutputStream out, String reason) {
        List<Es> chosen = new ArrayList<>();
        if (origPmt != null) {
            for (Es e : origPmt.es) {
                PidInfo i = pids.get(e.pid);
                if (wanted(e) && i != null && i.count > 0) chosen.add(new Es(e.stype, e.pid, stripCa(e.desc)));
            }
            prog = origPmt.prog;
            outProgDesc = stripCa(origPmt.progDesc);
        } else {
            for (Map.Entry<Integer, PidInfo> en : new TreeMap<>(pids).entrySet())
                if (en.getValue().guess != null) chosen.add(en.getValue().guess);
            if (serviceId != null) prog = serviceId;
        }
        Set<Integer> esPids = new HashSet<>();
        for (Es e : chosen) esPids.add(e.pid);
        int pp = origPmtPid != null ? origPmtPid : 0x1000;
        while (esPids.contains(pp) || pp == 0 || pp == NULL_PID) pp++;
        outPmtPid = pp;
        outEs = chosen;
        outPcrPid = choosePcr(chosen);
        allowed.clear();
        allowed.addAll(esPids);
        if (outPcrPid != NULL_PID) allowed.add(outPcrPid);
        if (out != null) pmtVer = (pmtVer + 1) & 0x1F;
        patSec = buildPat(tsid, prog, outPmtPid, 0);
        pmtSec = buildPmt(prog, pmtVer, outPcrPid, outProgDesc, outEs);
        StringBuilder sb = new StringBuilder();
        for (Es e : chosen) sb.append("\n   + ").append(e.describe());
        log.i(String.format("[%s] PMT nova (%s) prog 0x%X PCR 0x%X:%s", name, reason, prog, outPcrPid, sb));
        if (out != null) emitPsi(out);
    }

    private int choosePcr(List<Es> es) {
        if (origPmt != null) {
            PidInfo i = pids.get(origPmt.pcrPid);
            if (i != null && i.hasPcr) return origPmt.pcrPid;
        }
        for (Es e : es) {
            PidInfo i = pids.get(e.pid);
            if ("video".equals(e.kind()) && i != null && i.hasPcr) return e.pid;
        }
        for (Es e : es) {
            PidInfo i = pids.get(e.pid);
            if (i != null && i.hasPcr) return e.pid;
        }
        for (Map.Entry<Integer, PidInfo> en : new TreeMap<>(pids).entrySet())
            if (en.getValue().hasPcr) return en.getKey();
        return NULL_PID;
    }

    private void maybeLateEs(int pid, PidInfo info, ByteArrayOutputStream out) {
        if (origPmt == null || !info.pusiSeen) return;
        for (Es e : origPmt.es) {
            if (e.pid == pid && wanted(e)) {
                log.i(String.format("[%s] PID 0x%X apareceu - a acrescentar", name, pid));
                buildOutput(out, "PID novo");
                return;
            }
        }
    }

    // ------------------------------------------------------------------ saída
    private int packetize(int pid, byte[] section, int cc, ByteArrayOutputStream out) {
        byte[] data = new byte[section.length + 1];
        System.arraycopy(section, 0, data, 1, section.length);
        int pos = 0;
        boolean first = true;
        while (pos < data.length) {
            int n = Math.min(184, data.length - pos);
            byte[] p = new byte[TS];
            p[0] = SYNC;
            p[1] = (byte) ((first ? 0x40 : 0) | ((pid >> 8) & 0x1F));
            p[2] = (byte) pid;
            p[3] = (byte) (0x10 | cc);
            System.arraycopy(data, pos, p, 4, n);
            for (int k = 4 + n; k < TS; k++) p[k] = (byte) 0xFF;
            out.write(p, 0, TS);
            cc = (cc + 1) & 0x0F;
            pos += n;
            first = false;
        }
        return cc;
    }

    private void emitPsi(ByteArrayOutputStream out) {
        ccPat = packetize(0, patSec, ccPat, out);
        ccPmt = packetize(outPmtPid, pmtSec, ccPmt, out);
        lastPsiTime = elapsed();
        sincePsi = 0;
    }

    private boolean isVideo(int pid) {
        for (Es e : outEs) if (e.pid == pid) return "video".equals(e.kind());
        return false;
    }

    private boolean isEs(int pid) {
        for (Es e : outEs) if (e.pid == pid) return true;
        return false;
    }

    private boolean isKeyframeStart(int pid, byte[] p) {
        boolean af = (p[3] & 0x20) != 0;
        if (af && u(p, 4) > 0 && (p[5] & 0x40) != 0) return true; // random_access_indicator
        int off = 4 + (af ? 1 + u(p, 4) : 0);
        if (off + 9 > TS || p[off] != 0 || p[off + 1] != 0 || p[off + 2] != 1) return false;
        int d = off + 9 + u(p, off + 8);
        int stype = 0;
        for (Es e : outEs) if (e.pid == pid) stype = e.stype;
        int k = indexOfStartCode(p, d, TS);
        while (k >= 0 && k + 3 < TS) {
            int b = u(p, k + 3);
            if ((stype == 0x01 || stype == 0x02) && b == 0xB3) return true;
            if (stype == 0x1B && ((b & 0x1F) == 5 || (b & 0x1F) == 7)) return true;
            if (stype == 0x24) {
                int t = (b >> 1) & 0x3F;
                if ((t >= 16 && t <= 21) || t == 32 || t == 33) return true;
            }
            k = indexOfStartCode(p, k + 3, TS);
        }
        return false;
    }

    private static byte[] stripPayload(byte[] p) {
        int afLen = u(p, 4);
        byte[] r = new byte[TS];
        r[0] = p[0];
        r[1] = (byte) (p[1] & ~0x40);
        r[2] = p[2];
        r[3] = (byte) (0x20 | ((u(p, 3) - 1) & 0x0F));
        r[4] = (byte) 183;
        int n = Math.min(afLen, 183);
        System.arraycopy(p, 5, r, 5, n);
        for (int k = 5 + n; k < TS; k++) r[k] = (byte) 0xFF;
        return r;
    }

    private void emit(int pid, byte[] pkt, boolean pusi, ByteArrayOutputStream out) {
        if ((pkt[3] & 0xC0) != 0) {
            scrDropped++;
            double t = elapsed();
            if (scrLastLog == null || t - scrLastLog >= 5) {
                if (scrLastLog != null) {
                    log.w("[" + name + "] " + scrDropped + " pacotes encriptados descartados (falha do softcam?)");
                    scrDropped = 0;
                }
                scrLastLog = t;
            }
            if (pid == outPcrPid && (pkt[3] & 0x20) != 0 && u(pkt, 4) >= 7 && (pkt[5] & 0x10) != 0) {
                sincePsi++;
                byte[] s = stripPayload(pkt);
                out.write(s, 0, TS);
            }
            return;
        }
        if (!started.contains(pid)) {
            if (isVideo(pid) && pusi) {
                videoPesSkipped++;
                if (!isKeyframeStart(pid, pkt) && videoPesSkipped < 150) pusi = false;
            }
            if (pusi) {
                started.add(pid);
            } else if (isEs(pid)) {
                if (pid == outPcrPid && (pkt[3] & 0x20) != 0 && u(pkt, 4) > 0) pkt = stripPayload(pkt);
                else return;
            }
        }
        sincePsi++;
        if (sincePsi >= repeatPackets || (lastPsiTime != null && elapsed() - lastPsiTime >= repeatSecs))
            emitPsi(out);
        out.write(pkt, 0, TS);
    }

    /** Service id = 4.º campo (hex) da referência Enigma2. */
    public static Integer serviceIdFromRef(String ref) {
        String[] p = ref.split(":");
        if (p.length < 4) return null;
        try {
            return Integer.parseInt(p[3], 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
