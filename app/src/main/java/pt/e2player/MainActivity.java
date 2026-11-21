package pt.e2player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pt.e2player.core.Event;
import pt.e2player.core.Model;
import pt.e2player.core.OpenWebif;
import pt.e2player.core.StreamProxy;
import pt.e2player.core.TsFixer;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity {
    static final String TAG = "e2player";
    static final int LIST = 0, FULL = 1;

    // estado partilhado com o TvView
    int mode = LIST;
    boolean focusGroups = true;
    int gi, ci, gtop, ctop;
    final Model model = new Model();
    Model.Channel playing, prevCh;
    Model.Group playGroup, prevGroup;
    String status = "A ligar à box…";
    Model.Channel epgCh;
    List<Event> epgEvents;
    int epgSel, epgTop;
    boolean infobarVisible;
    String toastText;
    long toastUntil;
    String numbuf = "";

    private SharedPreferences prefs;
    private FrameLayout root;
    private PlayerView playerView;
    private TvView tv;
    private ExoPlayer player;
    private StreamProxy proxy;
    private OpenWebif owif;
    private ExecutorService pool;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Object> picons = new HashMap<>();
    private static final Object LOADING = new Object(), NONE = new Object();
    private int retry;
    private long lastBack;
    private int loadGen;

    private final TsFixer.Log proxyLog = new TsFixer.Log() {
        public void i(String m) { Log.i(TAG, m); }

        public void w(String m) { Log.w(TAG, m); }
    };

    // ------------------------------------------------------------------ ciclo de vida
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("e2player", MODE_PRIVATE);
        String favs = prefs.getString("favorites", "");
        for (String f : favs.split("\n")) if (!f.trim().isEmpty()) model.favorites.add(f.trim());

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setFocusable(false);
        playerView.setShutterBackgroundColor(Color.BLACK);
        root.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tv = new TvView(this);
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleMode();
            }
        });
        root.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        pool = Executors.newFixedThreadPool(4);
        initPlayer();
        ui.postDelayed(ticker, 1000);
        if (prefs.getString("box", "").isEmpty()) showSettings(true);
        else startServices();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.stop();   // liberta o tuner da box
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (playing != null) play(playing, playGroup, false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        if (proxy != null) proxy.stop();
        pool.shutdownNow();
    }

    private final Runnable ticker = new Runnable() {
        int n;

        public void run() {
            n++;
            if (mode == LIST || infobarVisible || toastText != null) tv.invalidate();
            if (n % 30 == 0) refreshStaleEpg();
            ui.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ player
    private void initPlayer() {
        DefaultExtractorsFactory ef = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
        // EXTENSION_RENDERER_MODE_ON: usa o descodificador do sistema quando existe e o
        // FFmpeg (MP2, AC-3, ...) quando o sistema não suporta o formato
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        Log.i(TAG, "descodificador FFmpeg disponível: " + ffmpegAvailable());
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 30000, 1000, 2000).build();
        player = new ExoPlayer.Builder(this, rf)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this, ef))
                .setLoadControl(lc)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                Log.w(TAG, "erro do player: " + e.getErrorCodeName(), e);
                onPlaybackFailed(e.getErrorCodeName());
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                checkAudio(tracks);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) onPlaybackFailed("fim do stream");
                if (state == Player.STATE_READY) retry = 0;
            }
        });
        playerView.setPlayer(player);
    }

    private static boolean ffmpegAvailable() {
        try {
            Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Avisa se o canal tem áudio mas nenhuma faixa pode ser descodificada. */
    private void checkAudio(Tracks tracks) {
        boolean hasAudio = false, playable = false;
        String mime = null;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < g.length; i++) {
                hasAudio = true;
                if (mime == null) mime = g.getTrackFormat(i).sampleMimeType;
                if (g.isTrackSupported(i)) playable = true;
            }
        }
        if (hasAudio && !playable) {
            Log.w(TAG, "áudio não suportado: " + mime + " (FFmpeg: " + ffmpegAvailable() + ")");
            toast("Sem descodificador para o áudio deste canal (" + mime + ")", 6000);
        }
    }

    void play(Model.Channel ch, Model.Group group, boolean keepRetry) {
        if (ch == null || proxy == null) return;
        if (playing != null && playing != ch) {
            prevCh = playing;
            prevGroup = playGroup;
        }
        playing = ch;
        playGroup = group != null ? group : (playGroup != null ? playGroup : ch.group);
        if (!keepRetry) retry = 0;
        player.setMediaItem(MediaItem.fromUri(proxy.url(ch.ref)));
        player.prepare();
        player.setPlayWhenReady(true);
        prefs.edit().putString("last", ch.ref).apply();
        showInfobar(5000);
        tv.invalidate();
    }

    private void onPlaybackFailed(String why) {
        final Model.Channel ch = playing;
        if (ch == null) return;
        if (retry < 3) {
            retry++;
            toast("Sinal perdido, a religar… (" + retry + "/3)", 2500);
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (playing == ch) play(ch, playGroup, true);
                }
            }, 1500L * retry);
        } else {
            toast("Não foi possível reproduzir " + ch.name + " (" + why + ")", 6000);
        }
    }

    void zap(int step) {
        if (playing == null) return;
        Model.Group g = playGroup != null ? playGroup : playing.group;
        play(model.neighbour(g, playing, step), g, false);
    }

    /** Percorre as faixas de áudio (TRACK_TYPE_AUDIO) ou legendas (TEXT). */
    private void cycleTrack(int type, String label, boolean allowOff) {
        List<Tracks.Group> gs = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        int cur = -1;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                if (g.isTrackSelected(i)) cur = gs.size();
                gs.add(g);
                idx.add(i);
            }
        }
        if (gs.isEmpty()) {
            toast("Sem " + label, 2000);
            return;
        }
        int n = gs.size() + (allowOff ? 1 : 0);
        int next = (cur + 1) % n;
        TrackSelectionParameters.Builder pb = player.getTrackSelectionParameters().buildUpon();
        if (next >= gs.size()) {
            player.setTrackSelectionParameters(pb.setTrackTypeDisabled(type, true).build());
            toast(cap(label) + ": desligadas", 2000);
            return;
        }
        Tracks.Group g = gs.get(next);
        int i = idx.get(next);
        player.setTrackSelectionParameters(pb.setTrackTypeDisabled(type, false)
                .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i)).build());
        toast(cap(label) + ": " + describe(g.getTrackFormat(i), next + 1), 2000);
    }

    private static String cap(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static String describe(Format f, int n) {
        StringBuilder b = new StringBuilder();
        if (f.label != null) b.append(f.label);
        else if (f.language != null && !f.language.equals("und")) b.append(f.language.toUpperCase());
        else b.append("faixa ").append(n);
        if (f.sampleMimeType != null) {
            String m = f.sampleMimeType;
            if (m.contains("ac3") || m.contains("eac3")) b.append(" (Dolby)");
            else if (m.contains("mpeg")) b.append(" (MPEG)");
            else if (m.contains("mp4a")) b.append(" (AAC)");
        }
        return b.toString();
    }

    void positionVideo() {
        FrameLayout.LayoutParams lp;
        if (mode == FULL || tv.preview.width() <= 0) {
            lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        } else {
            lp = new FrameLayout.LayoutParams((int) tv.preview.width(), (int) tv.preview.height());
            lp.leftMargin = (int) tv.preview.left;
            lp.topMargin = (int) tv.preview.top;
        }
        lp.gravity = Gravity.TOP | Gravity.START;
        playerView.setLayoutParams(lp);
    }

    // ------------------------------------------------------------------ dados
    private void startServices() {
        final String box = prefs.getString("box", "");
        final int web = prefs.getInt("web_port", 80), stream = prefs.getInt("stream_port", 8001);
        final String user = prefs.getString("user", ""), pass = prefs.getString("password", "");
        final boolean radio = prefs.getBoolean("radio", false);
        if (proxy != null) proxy.stop();
        if (player != null) player.stop();
        playing = null;
        model.load(new ArrayList<OpenWebif.Bouquet>());
        model.groups.clear();
        picons.clear();
        status = "A carregar canais de " + box + "…";
        tv.invalidate();
        owif = new OpenWebif(box, web, user, pass);
        proxy = new StreamProxy(box, stream, user, pass, proxyLog);
        final StreamProxy px = proxy;
        final OpenWebif ow = owif;
        final int gen = ++loadGen;
        pool.execute(new Runnable() {
            public void run() {
                List<OpenWebif.Bouquet> bq = null;
                Exception err = null;
                try {
                    px.start();
                    bq = ow.bouquets(radio);
                } catch (Exception e) {
                    err = e;
                }
                final List<OpenWebif.Bouquet> fb = bq;
                final Exception fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (gen == loadGen) onChannels(fb, fe);
                    }
                });
            }
        });
    }

    private void onChannels(List<OpenWebif.Bouquet> bq, Exception err) {
        if (err != null || bq == null) {
            Log.w(TAG, "canais", err);
            status = "Não foi possível obter os canais da box:\n" + (err != null ? err.getMessage() : "lista vazia")
                    + "\n\nVerifique o IP e as portas em ⚙ Definições (tecla MENU).";
            model.rebuildGroups();
            gi = model.groups.size() - 1;
            focusGroups = true;
            mode = LIST;
            positionVideo();
            tv.invalidate();
            return;
        }
        model.load(bq);
        selectGroup(0);
        Model.Channel last = model.byKey.get(Model.normref(prefs.getString("last", "")));
        if (last != null) {
            play(last, last.group, false);
            goFull();
        } else {
            focusGroups = true;
            tv.invalidate();
        }
        refreshEpg();
    }

    private final Runnable epgRefresher = new Runnable() {
        public void run() {
            refreshEpg();
        }
    };

    private void refreshEpg() {
        for (Model.Group g : model.bouquets) if (g.bref != null) loadNowNext(g.bref);
        ui.removeCallbacks(epgRefresher);
        ui.postDelayed(epgRefresher, 10 * 60 * 1000);
    }

    private void loadNowNext(final String bref) {
        final OpenWebif ow = owif;
        model.markEpgTried(bref);
        pool.execute(new Runnable() {
            public void run() {
                final Map<String, List<Event>> d = ow.epgNowNext(bref);
                ui.post(new Runnable() {
                    public void run() {
                        model.mergeEpg(bref, d);
                        tv.invalidate();
                    }
                });
            }
        });
    }

    private void refreshStaleEpg() {
        List<Model.Channel> chans = new ArrayList<>();
        Model.Group g = currentGroup();
        if (g != null) {
            List<Model.Channel> l = g.channels;
            chans.addAll(l.subList(Math.min(ctop, l.size()), Math.min(ctop + 30, l.size())));
        }
        if (playing != null) chans.add(playing);
        Set<String> stale = model.staleBrefs(chans, System.currentTimeMillis() / 1000, 60000);
        for (String b : stale) loadNowNext(b);
    }

    Bitmap picon(Model.Channel ch) {
        Object o = picons.get(ch.key);
        if (o == null) {
            picons.put(ch.key, LOADING);
            final String key = ch.key, ref = ch.ref;
            final OpenWebif ow = owif;
            pool.execute(new Runnable() {
                public void run() {
                    byte[] d = ow.picon(ref);
                    final Bitmap bmp = d != null ? BitmapFactory.decodeByteArray(d, 0, d.length) : null;
                    ui.post(new Runnable() {
                        public void run() {
                            picons.put(key, bmp != null ? bmp : NONE);
                            tv.invalidate();
                        }
                    });
                }
            });
            return null;
        }
        return o instanceof Bitmap ? (Bitmap) o : null;
    }

    // ------------------------------------------------------------------ EPG completo
    void openEpg(final Model.Channel ch) {
        if (ch == null) return;
        mode = LIST;
        epgCh = ch;
        epgEvents = null;
        epgSel = epgTop = 0;
        infobarVisible = false;
        positionVideo();
        tv.invalidate();
        final OpenWebif ow = owif;
        pool.execute(new Runnable() {
            public void run() {
                List<Event> l;
                try {
                    l = ow.epgService(ch.ref);
                } catch (Exception e) {
                    Log.w(TAG, "guia", e);
                    l = new ArrayList<>();
                }
                long now = System.currentTimeMillis() / 1000;
                final List<Event> fut = new ArrayList<>();
                for (Event ev : l) if (ev.end() > now) fut.add(ev);
                ui.post(new Runnable() {
                    public void run() {
                        if (epgCh == ch) {
                            epgEvents = fut;
                            epgSel = 0;
                            tv.invalidate();
                        }
                    }
                });
            }
        });
    }

    void closeEpg() {
        epgCh = null;
        tv.invalidate();
    }

    // ------------------------------------------------------------------ navegação
    static int scroll(int sel, int top, int rows, int n) {
        if (sel < top) top = sel;
        else if (sel >= top + rows) top = sel - rows + 1;
        return Math.max(0, Math.min(top, Math.max(0, n - rows)));
    }

    Model.Group currentGroup() {
        if (model.groups.isEmpty()) return null;
        gi = Math.max(0, Math.min(gi, model.groups.size() - 1));
        return model.groups.get(gi);
    }

    Model.Channel cursorChannel() {
        Model.Group g = currentGroup();
        if (g == null || g.channels.isEmpty()) return null;
        return g.channels.get(Math.max(0, Math.min(ci, g.channels.size() - 1)));
    }

    void selectGroup(int i) {
        if (model.groups.isEmpty()) return;
        gi = Math.max(0, Math.min(i, model.groups.size() - 1));
        List<Model.Channel> chs = model.groups.get(gi).channels;
        int p = playing != null ? chs.indexOf(playing) : -1;
        ci = Math.max(0, p);
        ctop = 0;
    }

    void toggleMode() {
        if (mode == FULL) openList();
        else if (playing != null) goFull();
    }

    void openList() {
        mode = LIST;
        infobarVisible = false;
        if (playing != null && !model.groups.isEmpty()) {
            selectGroup(model.groupIndexFor(playing, playGroup));
            focusGroups = false;
        }
        positionVideo();
        tv.invalidate();
    }

    void goFull() {
        mode = FULL;
        epgCh = null;
        positionVideo();
        showInfobar(5000);
        tv.invalidate();
    }

    private final Runnable hideInfobar = new Runnable() {
        public void run() {
            infobarVisible = false;
            tv.invalidate();
        }
    };

    void showInfobar(long ms) {
        if (mode != FULL || playing == null) return;
        infobarVisible = true;
        ui.removeCallbacks(hideInfobar);
        ui.postDelayed(hideInfobar, ms);
        tv.invalidate();
    }

    void toast(String s, long ms) {
        toastText = s;
        toastUntil = System.currentTimeMillis() + ms;
        tv.invalidate();
        ui.postDelayed(new Runnable() {
            public void run() {
                if (System.currentTimeMillis() >= toastUntil) {
                    toastText = null;
                    tv.invalidate();
                }
            }
        }, ms + 50);
    }

    private final Runnable numCommit = new Runnable() {
        public void run() {
            commitNumber();
        }
    };

    private void numberKey(int d) {
        if (numbuf.length() >= 4) numbuf = "";
        numbuf += d;
        ui.removeCallbacks(numCommit);
        ui.postDelayed(numCommit, 2000);
        tv.invalidate();
    }

    private void commitNumber() {
        ui.removeCallbacks(numCommit);
        String b = numbuf;
        numbuf = "";
        tv.invalidate();
        if (b.isEmpty()) return;
        Model.Channel ch = model.byNum.get(Integer.parseInt(b));
        if (ch == null) {
            toast("Canal " + b + " não existe", 2000);
            return;
        }
        play(ch, ch.group, false);
        if (mode == LIST) openList();
    }

    private void move(int step, boolean page) {
        if (epgCh != null) {
            if (epgEvents != null && !epgEvents.isEmpty()) {
                int s = epgSel + step * (page ? tv.epgRows() : 1);
                epgSel = Math.max(0, Math.min(epgEvents.size() - 1, s));
            }
            tv.invalidate();
            return;
        }
        if (model.groups.isEmpty()) return;
        if (focusGroups) {
            int n = model.groups.size();
            int i = gi + step * (page ? tv.groupRows() : 1);
            selectGroup(page ? Math.max(0, Math.min(n - 1, i)) : ((i % n) + n) % n);
        } else {
            Model.Group g = currentGroup();
            int n = g.channels.size();
            if (n > 0) {
                int i = ci + step * (page ? tv.chanRows() : 1);
                ci = page ? Math.max(0, Math.min(n - 1, i)) : ((i % n) + n) % n;
            }
        }
        tv.invalidate();
    }

    private void activate() {
        Model.Group g = currentGroup();
        if (g == null) return;
        if (focusGroups) {
            if (g.kind == Model.Group.SETTINGS) {
                showSettings(false);
                return;
            }
            if (!g.channels.isEmpty()) focusGroups = false;
            tv.invalidate();
            return;
        }
        Model.Channel ch = cursorChannel();
        if (ch == null) return;
        if (ch == playing) goFull();
        else play(ch, g, false);
    }

    private void toggleFav(Model.Channel ch) {
        if (ch == null) return;
        Model.Group cur = currentGroup();
        boolean fav = model.toggleFav(ch);
        int idx = model.groups.indexOf(cur);
        if (idx >= 0) gi = idx;
        else selectGroup(Math.min(gi, model.groups.size() - 1));
        StringBuilder sb = new StringBuilder();
        for (String k : model.favorites) sb.append(k).append('\n');
        prefs.edit().putString("favorites", sb.toString()).apply();
        toast(fav ? "★ " + ch.name + " adicionado aos favoritos" : ch.name + " removido dos favoritos", 2500);
        tv.invalidate();
    }

    private void back() {
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            finish();
            return;
        }
        lastBack = now;
        toast("Prima VOLTAR outra vez para sair", 2000);
    }

    // ------------------------------------------------------------------ teclas
    private static int digit(int k) {
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) return k - KeyEvent.KEYCODE_0;
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) return k - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    private static boolean isOk(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN
                || k == KeyEvent.KEYCODE_VOLUME_MUTE || k == KeyEvent.KEYCODE_HOME
                || k == KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) onKey(k);
        return true;
    }

    private void onKey(int k) {
        int d = digit(k);
        if (d >= 0) {
            numberKey(d);
            return;
        }
        if (!numbuf.isEmpty() && isOk(k)) {
            commitNumber();
            return;
        }
        switch (k) {
            case KeyEvent.KEYCODE_PROG_RED:
                toggleFav(mode == FULL ? playing : cursorChannel());
                if (mode == FULL) showInfobar(4000);
                return;
            case KeyEvent.KEYCODE_PROG_GREEN:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
                cycleTrack(C.TRACK_TYPE_AUDIO, "áudio", false);
                return;
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_CAPTIONS:
                cycleTrack(C.TRACK_TYPE_TEXT, "legendas", true);
                return;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                showSettings(false);
                return;
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                if (prevCh != null) play(prevCh, prevGroup, false);
                return;
            default:
                break;
        }
        if (mode == FULL) keyFull(k);
        else keyList(k);
    }

    private void keyFull(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                zap(1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                zap(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_INFO:
                if (infobarVisible && k == KeyEvent.KEYCODE_INFO) hideInfobar.run();
                else showInfobar(8000);
                break;
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_PROG_BLUE:
                openEpg(playing);
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (infobarVisible) hideInfobar.run();
                else back();
                break;
            default:
                if (isOk(k)) openList();
        }
    }

    private void keyList(int k) {
        if (epgCh != null) {
            switch (k) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_GUIDE:
                case KeyEvent.KEYCODE_PROG_BLUE:
                    closeEpg();
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    move(-1, false);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    move(1, false);
                    break;
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    move(-1, true);
                    break;
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    move(1, true);
                    break;
                default:
                    if (isOk(k)) {
                        Model.Channel ch = epgCh;
                        closeEpg();
                        if (ch != playing) play(ch, currentGroup(), false);
                    }
            }
            return;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                move(-1, false);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(1, false);
                break;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                move(-1, true);
                break;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                move(1, true);
                break;
            case KeyEvent.KEYCODE_MOVE_HOME:
                move(-1000000, true);
                break;
            case KeyEvent.KEYCODE_MOVE_END:
                move(1000000, true);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                focusGroups = true;
                tv.invalidate();
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                Model.Group g = currentGroup();
                if (g != null && !g.channels.isEmpty()) focusGroups = false;
                tv.invalidate();
                break;
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_PROG_BLUE:
                openEpg(cursorChannel());
                break;
            case KeyEvent.KEYCODE_INFO:
                if (playing != null) goFull();
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (playing != null) goFull();
                else back();
                break;
            default:
                if (isOk(k)) activate();
        }
    }

    // ------------------------------------------------------------------ definições
    private EditText field(LinearLayout l, String label, String value, int inputType) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setPadding(0, 18, 0, 2);
        l.addView(t);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setText(value);
        l.addView(e);
        return e;
    }

    private void showSettings(final boolean firstRun) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(48, 16, 48, 16);
        final EditText box = field(l, "IP da box Enigma2 (ex.: 192.168.1.65)", prefs.getString("box", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText web = field(l, "Porta do OpenWebif", String.valueOf(prefs.getInt("web_port", 80)),
                InputType.TYPE_CLASS_NUMBER);
        final EditText stream = field(l, "Porta de streaming", String.valueOf(prefs.getInt("stream_port", 8001)),
                InputType.TYPE_CLASS_NUMBER);
        final EditText user = field(l, "Utilizador (vazio se não tiver autenticação)", prefs.getString("user", ""),
                InputType.TYPE_CLASS_TEXT);
        final EditText pass = field(l, "Palavra-passe", prefs.getString("password", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final CheckBox radio = new CheckBox(this);
        radio.setText("Incluir bouquets de rádio");
        radio.setChecked(prefs.getBoolean("radio", false));
        l.addView(radio);
        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Definições da box Enigma2")
                .setView(sv)
                .setPositiveButton("Guardar", (dlg, w) -> {
                    prefs.edit()
                            .putString("box", box.getText().toString().trim())
                            .putInt("web_port", parseInt(web.getText().toString(), 80))
                            .putInt("stream_port", parseInt(stream.getText().toString(), 8001))
                            .putString("user", user.getText().toString().trim())
                            .putString("password", pass.getText().toString())
                            .putBoolean("radio", radio.isChecked())
                            .apply();
                    if (prefs.getString("box", "").isEmpty()) showSettings(true);
                    else startServices();
                })
                .setNegativeButton(firstRun ? "Sair" : "Cancelar", (dlg, w) -> {
                    if (firstRun) finish();
                });
        b.setCancelable(!firstRun);
        b.show();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
