package pt.e2player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import pt.e2player.core.Event;
import pt.e2player.core.Model;

/** Desenha toda a interface (listas, info, infobar, guia) por cima do vídeo. */
public class TvView extends View {
    static final int BG = Color.parseColor("#0b0e14");
    static final int PANEL = Color.parseColor("#131924");
    static final int PANEL2 = Color.parseColor("#1a2230");
    static final int SEL = Color.parseColor("#2563c9");
    static final int SEL_DIM = Color.parseColor("#2a3446");
    static final int TEXT = Color.parseColor("#e9edf3");
    static final int DIM = Color.parseColor("#8b95a7");
    static final int ACCENT = Color.parseColor("#f5a524");
    static final int PLAY = Color.parseColor("#4cc38a");
    static final int BAR_BG = Color.parseColor("#2a3242");
    static final int FAV = Color.parseColor("#f5c542");

    static final String[] DIAS = {"Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"};
    static final String[] MESES = {"Jan", "Fev", "Mar", "Abr", "Mai", "Jun",
            "Jul", "Ago", "Set", "Out", "Nov", "Dez"};

    private final MainActivity a;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tSmall = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tNorm = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tBold = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tBig = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tHuge = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());

    final RectF header = new RectF(), groups = new RectF(), chans = new RectF(),
            preview = new RectF(), info = new RectF(), infobar = new RectF();
    private float W, H;

    public TvView(Context c) {
        super(c);
        a = (MainActivity) c;
        tBold.setFakeBoldText(true);
        tBig.setFakeBoldText(true);
        tHuge.setFakeBoldText(true);
        setWillNotDraw(false);
    }

    // ------------------------------------------------------------------ layout
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        W = w;
        H = h;
        float base = Math.max(14f, h / 50f);
        tSmall.setTextSize(base * 0.85f);
        tNorm.setTextSize(base);
        tBold.setTextSize(base);
        tBig.setTextSize(base * 1.35f);
        tHuge.setTextSize(base * 2.2f);
        float hh = H * 0.07f;
        header.set(0, 0, W, hh);
        float top = hh + H * 0.015f, bottom = H - H * 0.025f;
        float gx = W * 0.015f, gw = W * 0.19f;
        float cx = gx + gw + W * 0.01f, cw = W * 0.37f;
        float vx = cx + cw + W * 0.01f, vw = W - vx - W * 0.015f, vh = vw * 9f / 16f;
        groups.set(gx, top, gx + gw, bottom);
        chans.set(cx, top, cx + cw, bottom);
        preview.set(vx, top, vx + vw, top + vh);
        info.set(vx, top + vh + H * 0.015f, vx + vw, bottom);
        infobar.set(W * 0.03f, H * 0.76f, W * 0.97f, H * 0.97f);
        post(new Runnable() {
            public void run() {
                a.positionVideo();
            }
        });
    }

    float groupRowH() {
        return tNorm.getFontSpacing() * 2.0f;
    }

    float chanRowH() {
        return tNorm.getFontSpacing() * 2.7f;
    }

    int groupRows() {
        return Math.max(1, (int) (groups.height() / groupRowH()));
    }

    int chanRows() {
        return Math.max(1, (int) (chans.height() / chanRowH()));
    }

    int epgRows() {
        return Math.max(1, (int) (chans.height() / groupRowH()));
    }

    // ------------------------------------------------------------------ util
    String hhmm(long t) {
        return hm.format(new Date(t * 1000));
    }

    static String dayLabel(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t * 1000);
        return String.format(Locale.getDefault(), "%s %02d/%02d", DIAS[c.get(Calendar.DAY_OF_WEEK) - 1],
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1);
    }

    static String clockLabel() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.getDefault(), "%s %d %s  %02d:%02d",
                DIAS[c.get(Calendar.DAY_OF_WEEK) - 1], c.get(Calendar.DAY_OF_MONTH),
                MESES[c.get(Calendar.MONTH)], c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private void rect(Canvas c, float l, float t, float r, float b, int color) {
        fill.setColor(color);
        c.drawRect(l, t, r, b, fill);
    }

    private void round(Canvas c, float l, float t, float r, float b, int color) {
        fill.setColor(color);
        float rad = H * 0.006f;
        c.drawRoundRect(new RectF(l, t, r, b), rad, rad, fill);
    }

    /** Texto numa linha, centrado verticalmente em cy, cortado com "…" se exceder maxW. */
    private float text(Canvas c, String s, float x, float cy, TextPaint p, int color,
                       float maxW, Paint.Align align) {
        if (s == null) s = "";
        if (maxW > 0) s = TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END).toString();
        p.setColor(color);
        p.setTextAlign(align);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2f, p);
        return p.measureText(s);
    }

    /** Texto no topo em y (como anchor "nw"). Devolve a altura usada. */
    private float textTop(Canvas c, String s, float x, float y, TextPaint p, int color, float maxW) {
        Paint.FontMetrics fm = p.getFontMetrics();
        text(c, s, x, y + (fm.descent - fm.ascent) / 2f, p, color, maxW, Paint.Align.LEFT);
        return p.getFontSpacing();
    }

    @SuppressWarnings("deprecation")
    private void paragraph(Canvas c, String s, float x, float y, float w, float bottom, TextPaint p, int color) {
        if (s == null || s.isEmpty() || bottom <= y || w <= 0) return;
        p.setColor(color);
        p.setTextAlign(Paint.Align.LEFT);
        StaticLayout l = new StaticLayout(s, p, (int) w, Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false);
        c.save();
        c.clipRect(x, y, x + w, bottom);
        c.translate(x, y);
        l.draw(c);
        c.restore();
    }

    private void bar(Canvas c, float x, float y, float w, float h, float frac) {
        rect(c, x, y, x + w, y + h, BAR_BG);
        rect(c, x, y, x + w * frac, y + h, ACCENT);
    }

    private void scrollbar(Canvas c, RectF r, int top, int rows, int n) {
        if (n <= rows) return;
        float bh = Math.max(20, r.height() * rows / n);
        float by = r.top + (r.height() - bh) * top / Math.max(1, n - rows);
        rect(c, r.right - 4, by, r.right, by + bh, SEL_DIM);
    }

    private void picon(Canvas c, Model.Channel ch, float x, float y, float boxH, float[] outW) {
        outW[0] = 0;
        Bitmap b = a.picon(ch);
        if (b == null || b.getHeight() == 0) return;
        float scale = Math.min(boxH / b.getHeight(), boxH * 2f / b.getWidth());
        float w = b.getWidth() * scale, h = b.getHeight() * scale;
        c.drawBitmap(b, null, new RectF(x, y + (boxH - h) / 2f, x + w, y + (boxH + h) / 2f), fill);
        outW[0] = w;
    }

    // ------------------------------------------------------------------ desenho
    @Override
    protected void onDraw(Canvas c) {
        if (W <= 0) return;
        if (a.mode == MainActivity.LIST) {
            // fundo à volta da pré-visualização (o vídeo fica visível no buraco)
            rect(c, 0, 0, W, preview.top, BG);
            rect(c, 0, preview.bottom, W, H, BG);
            rect(c, 0, preview.top, preview.left, preview.bottom, BG);
            rect(c, preview.right, preview.top, W, preview.bottom, BG);
            drawHeader(c);
            drawGroups(c);
            if (a.epgCh != null) drawEpg(c);
            else drawChannels(c);
            drawInfo(c);
        } else if (a.infobarVisible && a.playing != null) {
            drawInfobar(c);
        }
        drawOverlays(c);
    }

    private void drawHeader(Canvas c) {
        rect(c, header.left, header.top, header.right, header.bottom, PANEL);
        float pad = W * 0.015f, cy = header.centerY();
        float x = pad + text(c, "TV", pad, cy, tBig, ACCENT, 0, Paint.Align.LEFT) + tBig.measureText("  ");
        String g = "";
        if (!a.model.groups.isEmpty()) g = a.model.groups.get(a.gi).name;
        if (a.epgCh != null) g = "Guia de programas · " + a.epgCh.name;
        String clock = clockLabel();
        float cw = tBold.measureText(clock);
        text(c, g, x, cy, tBig, TEXT, W - x - cw - 3 * pad, Paint.Align.LEFT);
        text(c, clock, W - pad, cy, tBold, TEXT, 0, Paint.Align.RIGHT);
    }

    private void drawGroups(Canvas c) {
        RectF r = groups;
        rect(c, r.left, r.top, r.right, r.bottom, PANEL);
        List<Model.Group> gs = a.model.groups;
        float rh = groupRowH();
        int rows = groupRows();
        a.gtop = MainActivity.scroll(a.gi, a.gtop, rows, gs.size());
        float pad = 14;
        for (int i = a.gtop; i < Math.min(gs.size(), a.gtop + rows); i++) {
            Model.Group g = gs.get(i);
            float y = r.top + (i - a.gtop) * rh;
            boolean sel = i == a.gi;
            if (sel)
                round(c, r.left + 4, y + 2, r.right - 8, y + rh - 2,
                        a.focusGroups && a.epgCh == null ? SEL : SEL_DIM);
            float cy = y + rh / 2f;
            float cw = 0;
            if (g.kind != Model.Group.SETTINGS) {
                String cnt = String.valueOf(g.channels.size());
                cw = text(c, cnt, r.right - 16, cy, tSmall, sel ? TEXT : DIM, 0, Paint.Align.RIGHT);
            }
            text(c, g.name, r.left + pad, cy, tNorm, TEXT, r.width() - 2 * pad - cw - 24, Paint.Align.LEFT);
        }
        scrollbar(c, r, a.gtop, rows, gs.size());
    }

    private void drawChannels(Canvas c) {
        RectF r = chans;
        rect(c, r.left, r.top, r.right, r.bottom, PANEL);
        if (a.model.groups.isEmpty() || a.currentGroup() == null) {
            paragraph(c, a.status, r.left + 30, r.centerY() - tNorm.getFontSpacing() * 2,
                    r.width() - 60, r.bottom, tNorm, DIM);
            return;
        }
        Model.Group grp = a.currentGroup();
        if (grp.kind == Model.Group.SETTINGS) {
            String help = "Prima OK para alterar o IP da box, portas e palavra-passe.\n\n"
                            + "Teclas: CH+/CH- ou cima/baixo mudam de canal, OK abre a lista, "
                            + "números para zapping, VERMELHO favorito, VERDE áudio, AMARELO legendas, "
                            + "AZUL ou GUIA para o guia de programas, VOLTAR duas vezes para sair.";
            if (a.model.bouquets.isEmpty()) help = a.status + "\n\n" + help;
            paragraph(c, help, r.left + 30, r.top + 30, r.width() - 60, r.bottom, tNorm, DIM);
            return;
        }
        List<Model.Channel> chs = grp.channels;
        float rh = chanRowH();
        int rows = chanRows();
        a.ci = Math.max(0, Math.min(a.ci, chs.size() - 1));
        a.ctop = MainActivity.scroll(a.ci, a.ctop, rows, chs.size());
        float pad = 14;
        float numw = tBold.measureText("0000");
        float barw = r.width() * 0.16f;
        long now = System.currentTimeMillis() / 1000;
        for (int i = a.ctop; i < Math.min(chs.size(), a.ctop + rows); i++) {
            Model.Channel ch = chs.get(i);
            float y = r.top + (i - a.ctop) * rh;
            boolean sel = i == a.ci;
            if (sel) round(c, r.left + 4, y + 2, r.right - 8, y + rh - 2, a.focusGroups ? SEL_DIM : SEL);
            float y1 = y + rh * 0.33f, y2 = y + rh * 0.70f;
            text(c, String.valueOf(ch.num), r.left + pad + numw, y1, tBold, sel ? TEXT : DIM, 0, Paint.Align.RIGHT);
            float x = r.left + pad + numw + 16;
            float right = r.right - 18;
            if (ch == a.playing) {
                text(c, "▶", right, y1, tNorm, PLAY, 0, Paint.Align.RIGHT);
                right -= tNorm.measureText("▶ ");
            }
            if (a.model.isFav(ch)) {
                text(c, "★", right, y1, tNorm, FAV, 0, Paint.Align.RIGHT);
                right -= tNorm.measureText("★ ");
            }
            text(c, ch.name, x, y1, tBold, TEXT, right - x, Paint.Align.LEFT);
            Event ev = a.model.nowNext(ch, now)[0];
            if (ev != null) {
                text(c, ev.title, x, y2, tSmall, sel ? TEXT : DIM, r.right - x - barw - 36, Paint.Align.LEFT);
                bar(c, r.right - 18 - barw, y2 - 3, barw, 6, ev.progress(now));
            }
        }
        scrollbar(c, r, a.ctop, rows, chs.size());
    }

    /** Bloco "20:00 – 21:00 Título" + barra de progresso. Devolve o novo y. */
    private float eventBlock(Canvas c, Event ev, float x, float y, float width) {
        long now = System.currentTimeMillis() / 1000;
        String times = hhmm(ev.start) + " – " + hhmm(ev.end());
        float lh = tBold.getFontSpacing();
        float tw = text(c, times, x, y + lh / 2f, tBold, ACCENT, 0, Paint.Align.LEFT) + tBold.measureText("   ");
        text(c, ev.title, x + tw, y + lh / 2f, tBold, TEXT, width - tw, Paint.Align.LEFT);
        y += lh + 8;
        if (ev.start <= now) {
            String lbl = "+" + Math.max(0, (ev.end() - now) / 60) + " min";
            float lw = tSmall.measureText(lbl) + 12;
            bar(c, x, y + 4, width - lw, 7, ev.progress(now));
            text(c, lbl, x + width, y + 7, tSmall, DIM, 0, Paint.Align.RIGHT);
            y += 26;
        }
        return y;
    }

    private void drawInfo(Canvas c) {
        RectF r = info;
        rect(c, r.left, r.top, r.right, r.bottom, PANEL);
        float pad = 18, w = r.width() - 2 * pad;
        if (a.epgCh != null) {
            drawEpgInfo(c, r, pad, w);
            return;
        }
        Model.Channel ch = a.cursorChannel();
        if (ch == null) return;
        float ph = tBig.getFontSpacing() * 1.8f;
        float[] pw = new float[1];
        picon(c, ch, r.left + pad, r.top + pad, ph, pw);
        float x = r.left + pad + (pw[0] > 0 ? pw[0] + 16 : 0);
        float y = r.top + pad;
        y += textTop(c, ch.name, x, y, tBig, TEXT, r.right - pad - x);
        textTop(c, ch.num + " · " + ch.group.name, x, y, tSmall, DIM, r.right - pad - x);
        y = r.top + pad + ph + 16;
        long now = System.currentTimeMillis() / 1000;
        Event[] nn = a.model.nowNext(ch, now);
        if (nn[0] == null && nn[1] == null) {
            textTop(c, "Sem informação de programa", r.left + pad, y, tNorm, DIM, w);
            return;
        }
        if (nn[0] != null) y = eventBlock(c, nn[0], r.left + pad, y, w);
        if (nn[1] != null) {
            y += textTop(c, "A seguir  " + hhmm(nn[1].start) + "  " + nn[1].title, r.left + pad, y, tSmall, DIM, w) + 10;
        }
        if (nn[0] != null) paragraph(c, nn[0].desc, r.left + pad, y, w, r.bottom - pad, tSmall, DIM);
    }

    private void drawEpg(Canvas c) {
        RectF r = chans;
        rect(c, r.left, r.top, r.right, r.bottom, PANEL);
        if (a.epgEvents == null) {
            text(c, "A carregar o guia…", r.centerX(), r.centerY(), tNorm, DIM, 0, Paint.Align.CENTER);
            return;
        }
        if (a.epgEvents.isEmpty()) {
            text(c, "Sem guia de programas para este canal", r.centerX(), r.centerY(), tNorm, DIM,
                    r.width() - 20, Paint.Align.CENTER);
            return;
        }
        List<Event> evs = a.epgEvents;
        float rh = groupRowH();
        int rows = epgRows();
        a.epgSel = Math.max(0, Math.min(a.epgSel, evs.size() - 1));
        a.epgTop = MainActivity.scroll(a.epgSel, a.epgTop, rows, evs.size());
        float pad = 14;
        float dw = tSmall.measureText("Sáb 00/00  ");
        float tw = tBold.measureText("00:00   ");
        long now = System.currentTimeMillis() / 1000;
        String lastDay = null;
        for (int i = a.epgTop; i < Math.min(evs.size(), a.epgTop + rows); i++) {
            Event ev = evs.get(i);
            float y = r.top + (i - a.epgTop) * rh;
            if (i == a.epgSel) round(c, r.left + 4, y + 2, r.right - 8, y + rh - 2, SEL);
            float cy = y + rh / 2f;
            String d = dayLabel(ev.start);
            if (!d.equals(lastDay) || i == a.epgTop) {
                text(c, d, r.left + pad, cy, tSmall, DIM, 0, Paint.Align.LEFT);
                lastDay = d;
            }
            text(c, hhmm(ev.start), r.left + pad + dw, cy, tBold, ev.isLive(now) ? ACCENT : TEXT, 0, Paint.Align.LEFT);
            text(c, ev.title, r.left + pad + dw + tw, cy, tNorm, TEXT, r.width() - 2 * pad - dw - tw - 10,
                    Paint.Align.LEFT);
        }
        scrollbar(c, r, a.epgTop, rows, evs.size());
    }

    private void drawEpgInfo(Canvas c, RectF r, float pad, float w) {
        float y = r.top + pad;
        y += textTop(c, a.epgCh.name, r.left + pad, y, tBig, TEXT, w) + 10;
        if (a.epgEvents == null || a.epgEvents.isEmpty()) return;
        Event ev = a.epgEvents.get(Math.max(0, Math.min(a.epgSel, a.epgEvents.size() - 1)));
        y += textTop(c, dayLabel(ev.start), r.left + pad, y, tSmall, DIM, w) + 4;
        y = eventBlock(c, ev, r.left + pad, y, w);
        paragraph(c, ev.desc, r.left + pad, y + 4, w, r.bottom - pad, tSmall, DIM);
    }

    private void drawInfobar(Canvas c) {
        RectF r = infobar;
        Model.Channel ch = a.playing;
        round(c, r.left, r.top, r.right, r.bottom, PANEL);
        rect(c, r.left, r.top, r.left + 6, r.bottom, SEL);
        float pad = r.height() * 0.12f;
        String num = String.valueOf(ch.num);
        float x = r.left + pad + 6;
        textTop(c, num, x, r.top + pad * 0.6f, tHuge, ACCENT, 0);
        x += tHuge.measureText(num) + 24;
        float ph = r.height() * 0.42f;
        float[] pw = new float[1];
        picon(c, ch, x, r.top + pad, ph, pw);
        if (pw[0] > 0) x += pw[0] + 20;
        String clock = clockLabel();
        float cw = tBold.measureText(clock);
        textTop(c, clock, r.right - pad - cw, r.top + pad, tBold, TEXT, 0);
        float y = r.top + pad;
        y += textTop(c, ch.name, x, y, tBig, TEXT, r.right - pad - cw - 30 - x);
        textTop(c, ch.group.name + (a.model.isFav(ch) ? "   ★" : ""), x, y, tSmall, DIM, r.right - pad - x);
        long now = System.currentTimeMillis() / 1000;
        Event[] nn = a.model.nowNext(ch, now);
        float ex = r.left + pad + 6, ew = r.right - pad - ex;
        y = r.top + pad + ph + 10;
        if (nn[0] != null) y = eventBlock(c, nn[0], ex, y, ew);
        if (nn[1] != null)
            textTop(c, "A seguir   " + hhmm(nn[1].start) + "   " + nn[1].title, ex, y, tNorm, DIM, ew);
        if (nn[0] == null && nn[1] == null)
            textTop(c, "Sem informação de programa", ex, y, tNorm, DIM, ew);
    }

    private void drawOverlays(Canvas c) {
        float pad = W * 0.03f;
        float y = H * 0.04f;
        if (!a.numbuf.isEmpty()) {
            Model.Channel ch = a.model.byNum.get(Integer.parseInt(a.numbuf));
            String s = a.numbuf + (ch != null ? "  " + ch.name : "");
            float tw = Math.min(tHuge.measureText(s), W * 0.5f);
            float h = tHuge.getFontSpacing() * 1.4f;
            round(c, W - pad - tw - 40, y, W - pad, y + h, SEL);
            text(c, s, W - pad - 20, y + h / 2f, tHuge, TEXT, W * 0.5f, Paint.Align.RIGHT);
            y += h + 12;
        }
        if (a.toastText != null && System.currentTimeMillis() < a.toastUntil) {
            float tw = Math.min(tBold.measureText(a.toastText), W * 0.6f);
            float h = tBold.getFontSpacing() * 1.8f;
            round(c, W - pad - tw - 40, y, W - pad, y + h, PANEL2);
            text(c, a.toastText, W - pad - 20, y + h / 2f, tBold, TEXT, W * 0.6f, Paint.Align.RIGHT);
        }
    }
}
