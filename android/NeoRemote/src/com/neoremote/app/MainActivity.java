package com.neoremote.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.MediaController;
import android.widget.VideoView;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.content.pm.ActivityInfo;
import android.media.MediaPlayer;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity implements NeoBle.Listener {

    private static final int MODE_NONE = 0;
    private static final int MODE_MANUAL = 1;
    private static final int MODE_PATTERN = 2;
    private static final int MODE_TOUCH = 3;
    private static final int MODE_SOUND = 4;
    private static final int MODE_BUILTIN = 5;
    private static final int MODE_VIDEO = 6;

    private static final int REQ_BLE = 1;
    private static final int REQ_MIC = 2;
    private static final int REQ_BT_ON = 3;
    private static final int REQ_NOTIF = 4;
    private static final int REQ_PICK_VIDEO = 10;
    private static final int REQ_PICK_SCRIPT = 11;
    private static final int REQ_MIC_VIDEO = 12;

    // --- відео
    private VideoView video;
    private FrameLayout videoBox;
    private View videoControls, fsOverlay, headerView, tabRowView, stopBtnView;
    private LinearLayout rootView;
    private TextView videoName, scriptName, syncScriptBtn, syncAudioBtn, bySpeedBtn, byPosBtn, offsetT, videoPlaceholder;
    private Funscript script;
    private boolean syncByScript = true, scriptBySpeed = true, fullscreen = false, videoReady = false;
    private int offsetMs = 100;
    private final MusicMeter videoMeter = new MusicMeter();
    private static final int RANDOM_IDX = 100;
    private static final long ENGINE_MS = 50;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final SoundMeter sound = new SoundMeter();
    private final MusicMeter music = new MusicMeter();
    private boolean musicSource = false;   // false = мікрофон, true = музика на телефоні
    private int minPct = 0, maxPct = 100;  // межі сили, %
    private TextView srcMicBtn, srcMusicBtn, soundHint, sensTitle;
    private boolean askedNotif = false;
    private NeoBle ble;
    private SharedPreferences prefs;

    // стан рушія
    private int mode = MODE_NONE;
    private int manualPct = 50;
    private int masterPct = 100;
    private int patternIdx = -1;
    private int[] pattern;
    private int patPos;
    private long tick;
    private int builtinIdx = 0;          // 1..7, вбудований режим іграшки
    private int builtinStrength = NeoBle.DEFAULT_MODE_STRENGTH;
    private String statusBase = "";
    private int batteryPct = -1;

    // інтерфейс
    private TextView statusText, connectBtn;
    private View statusDot;
    private WaveView wave;
    private final List<TextView> tabs = new ArrayList<TextView>();
    private final List<View> pages = new ArrayList<View>();
    private int page = 0;
    private TextView manualBig, manualSub, manualStart;
    private SeekBar manualSeek;
    private final List<TextView> patternBtns = new ArrayList<TextView>();
    private final List<Integer> patternIds = new ArrayList<Integer>();
    private TouchPadView pad;
    private TextView loopBtn;
    private TextView micBtn, micLevel;

    // вибір пристрою
    private AlertDialog picker;
    private ArrayAdapter<String> pickerAdapter;
    private final List<BluetoothDevice> pickerDevs = new ArrayList<BluetoothDevice>();
    private boolean autoConnect;
    private Runnable pendingAfterPerms;

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("neo", MODE_PRIVATE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        ble = new NeoBle(this, this);
        ble.setKeepAlive(true);
        minPct = prefs.getInt("minPct", 0);
        maxPct = prefs.getInt("maxPct", 100);
        musicSource = prefs.getBoolean("musicSrc", false);
        NeoService.stopListener = new Runnable() {
            @Override public void run() { h.post(new Runnable() { @Override public void run() { stopAll(); } }); }
        };
        setContentView(buildUi());
        showPage(0);
        h.post(engine);
        onState(NeoBle.ST_IDLE, "Не підключено");
        tryAutoConnectSaved();
        restoreVideo();
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        NeoService.stopListener = null;
        NeoService.stop(this);
        sound.stop();
        music.stop();
        videoMeter.stop();
        ble.release();
        super.onDestroy();
    }

    // ================================================================ engine

    private final Runnable engine = new Runnable() {
        @Override public void run() {
            h.postDelayed(this, ENGINE_MS);
            tick++;
            if (pad != null && pad.isTouching() && mode != MODE_TOUCH) setMode(MODE_TOUCH);
            if (video != null && video.isPlaying() && mode != MODE_VIDEO) setMode(MODE_VIDEO);
            int pct = 0;
            switch (mode) {
                case MODE_MANUAL:
                    pct = manualPct;
                    break;
                case MODE_PATTERN:
                    if (pattern != null && pattern.length > 0) {
                        if (tick % (Patterns.STEP_MS / ENGINE_MS) == 0) patPos++;
                        if (patPos >= pattern.length) {
                            patPos = 0;
                            if (patternIdx == RANDOM_IDX) pattern = Patterns.random(rnd);
                        }
                        pct = pattern[patPos] * masterPct / 100;
                    }
                    break;
                case MODE_TOUCH:
                    pct = pad.sample();
                    break;
                case MODE_SOUND:
                    pct = musicSource ? music.value() : sound.value();
                    if (micLevel != null) micLevel.setText((musicSource ? "Музика" : "Гучність") + " → " + pct + "%");
                    break;
                case MODE_VIDEO:
                    if (video != null && video.isPlaying()) {
                        if (syncByScript && script != null) {
                            pct = script.valueAt(video.getCurrentPosition() + offsetMs, scriptBySpeed);
                        } else {
                            pct = videoMeter.value();
                        }
                    }
                    break;
                case MODE_BUILTIN:
                    // вбудований режим працює в самій іграшці; на графіку показуємо його силу
                    int bs = effectiveBuiltinStrength();
                    wave.push(bs * 10, "Вбудований режим " + builtinIdx + ", сила " + bs);
                    return;
                default:
                    break;
            }
            int out = limit(pct);
            int v = toPower(out);
            ble.setPower(v);
            wave.push(out, "Сила на іграшці: " + v + " / " + NeoBle.MAX_POWER
                    + (minPct > 0 || maxPct < 100 ? "   (межі " + minPct + "–" + maxPct + "%)" : ""));
        }
    };

    /** Застосовує межі сили: 0 лишається 0, решта стискається в [мін, макс]. */
    int limit(int pct) {
        if (pct <= 0) return 0;
        int lo = Math.min(minPct, maxPct), hi = Math.max(minPct, maxPct);
        int v = lo + Math.round((hi - lo) * Math.min(100, pct) / 100f);
        return Math.max(1, v);
    }

    /** Сила вбудованого режиму 1..10 з урахуванням максимуму. */
    int effectiveBuiltinStrength() {
        int cap = Math.max(1, Math.round(maxPct / 10f));
        return Math.max(1, Math.min(builtinStrength, cap));
    }

    /** 0..100 % → 0..255 */
    static int toPower(int pct) {
        if (pct <= 0) return 0;
        return Math.max(1, Math.min(NeoBle.MAX_POWER, Math.round(pct * NeoBle.MAX_POWER / 100f)));
    }

    private void setMode(int m) {
        if (mode == MODE_SOUND && m != MODE_SOUND) {
            sound.stop();
            music.stop();
        }
        if (m != MODE_PATTERN) {
            patternIdx = -1;
            pattern = null;
        }
        if (m != MODE_BUILTIN) builtinIdx = 0;
        if (mode == MODE_VIDEO && m != MODE_VIDEO) {
            videoMeter.stop();
            if (video != null && video.isPlaying()) video.pause();
        }
        if (m == MODE_VIDEO && mode != MODE_VIDEO) startVideoAudioIfNeeded();
        if (m != MODE_TOUCH && loopOn) {
            loopOn = false;
            if (pad != null) pad.setLoop(false);
        }
        mode = m;
        updateLoopBtn();
        updateManualUi();
        updatePatternButtons();
        updateMicBtn();
        updateService();
    }

    /** Сповіщення + робота з вимкненим екраном, поки щось активне і іграшка підключена. */
    private void updateService() {
        if (mode == MODE_NONE || !ble.isReady()) {
            NeoService.stop(this);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotif = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        NeoService.update(this, describeMode(), mode == MODE_SOUND && !musicSource);
    }

    private String describeMode() {
        switch (mode) {
            case MODE_MANUAL: return "Керування: " + manualPct + "%";
            case MODE_PATTERN:
                return patternIdx == RANDOM_IDX ? "Режим: випадкова гра"
                        : "Режим: " + (patternIdx >= 0 ? Patterns.NAMES[patternIdx] : "");
            case MODE_TOUCH: return "Дотик";
            case MODE_SOUND: return musicSource ? "Музика на телефоні" : "Звук з мікрофона";
            case MODE_BUILTIN: return "Вбудований режим " + builtinIdx;
            case MODE_VIDEO: return (syncByScript && script != null) ? "Відео за скриптом" : "Відео за звуком";
            default: return "";
        }
    }

    private void stopAll() {
        setMode(MODE_NONE);
        ble.stopNow();
    }

    // ================================================================ UI

    private int dp(float v) { return Ui.dp(this, v); }

    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    private LinearLayout.LayoutParams lpm(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        // --- заголовок і статус
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(Ui.text(this, "Alex Neo 2", 22, Ui.TEXT, true));
        LinearLayout st = new LinearLayout(this);
        st.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        st.addView(statusDot, lpm(dp(10), dp(10), 0, 2, 8, 0));
        statusText = Ui.text(this, "", 13, Ui.TEXT_DIM, false);
        statusText.setMaxLines(2);
        st.addView(statusText);
        titleBox.addView(st);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        connectBtn = Ui.button(this, "Підключити", Ui.ACCENT, 0);
        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onConnectClicked(); }
        });
        header.addView(connectBtn);
        root.addView(header);
        rootView = root;
        headerView = header;

        // --- графік
        wave = new WaveView(this);
        wave.setBackground(Ui.round(this, Ui.CARD, 18));
        root.addView(wave, lpm(ViewGroup.LayoutParams.MATCH_PARENT, dp(120), 0, 12, 0, 10));

        // --- вкладки (рівні частини ширини, без прокрутки)
        LinearLayout tabRow = new LinearLayout(this);
        String[] names = {"Керування", "Вбудовані", "Режими", "Дотик", "Звук", "Відео"};
        float[] weights = {1.32f, 1.27f, 1.05f, 0.95f, 0.82f, 0.9f};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = Ui.text(this, names[i], 11.5f, Ui.TEXT, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(dp(2), dp(9), dp(2), dp(9));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showPage(idx); }
            });
            tabs.add(t);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights[i]);
            tp.setMargins(i == 0 ? 0 : dp(2), 0, i == names.length - 1 ? 0 : dp(2), 0);
            tabRow.addView(t, tp);
        }
        tabRowView = tabRow;
        root.addView(tabRow, lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10));

        // --- сторінки
        FrameLayout content = new FrameLayout(this);
        pages.add(buildManualPage());
        pages.add(buildBuiltinPage());
        pages.add(buildPatternsPage());
        pages.add(buildTouchPage());
        pages.add(buildSoundPage());
        pages.add(buildVideoPage());
        for (View p : pages) content.addView(p, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // --- СТОП
        TextView stop = Ui.button(this, "■  СТОП", Ui.RED, 0);
        stop.setTextSize(17);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopAll(); }
        });
        root.addView(stop, lpm(-1, dp(56), 0, 10, 0, 0));
        stopBtnView = stop;
        return root;
    }

    private void showPage(int idx) {
        page = idx;
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).setVisibility(i == idx ? View.VISIBLE : View.GONE);
            TextView t = tabs.get(i);
            Ui.bg(t, i == idx ? Ui.ACCENT : Ui.CARD, 0, 20);
            t.setTextColor(i == idx ? 0xFFFFFFFF : Ui.TEXT_DIM);
        }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(this, Ui.CARD, 18));
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        return c;
    }

    private ScrollView scroll(View inner) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.addView(inner);
        return s;
    }

    // ---------------------------------------------------------------- Керування

    private View buildManualPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout c = card();
        manualBig = Ui.text(this, "", 44, Ui.TEXT, true);
        manualBig.setGravity(Gravity.CENTER);
        c.addView(manualBig, lp(-1, -2));
        manualSub = Ui.text(this, "", 13, Ui.TEXT_DIM, false);
        manualSub.setGravity(Gravity.CENTER);
        c.addView(manualSub, lp(-1, -2));
        c.addView(Ui.text(this, "Пересувайте повзунок, щоб змінити силу", 13, Ui.TEXT_DIM, false),
                lpm(-2, -2, 0, 14, 0, 0));
        manualSeek = Ui.seek(this, 100, manualPct);
        manualSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                manualPct = p;
                if (p > 0 && mode != MODE_MANUAL) setMode(MODE_MANUAL);
                updateManualUi();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        c.addView(manualSeek, lpm(-1, dp(44), 0, 6, 0, 0));
        LinearLayout scale = new LinearLayout(this);
        TextView a = Ui.text(this, "0%", 12, Ui.TEXT_DIM, false);
        TextView b = Ui.text(this, "100%", 12, Ui.TEXT_DIM, false);
        b.setGravity(Gravity.END);
        scale.addView(a, new LinearLayout.LayoutParams(0, -2, 1));
        scale.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(scale);
        col.addView(c, lp(-1, -2));

        LinearLayout quick = new LinearLayout(this);
        String[] qn = {"Слабко", "Середньо", "Макс"};
        final int[] qv = {33, 66, 100};
        for (int i = 0; i < qn.length; i++) {
            final int v = qv[i];
            TextView q = Ui.button(this, qn[i], Ui.CARD, Ui.ACCENT_DIM);
            q.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    manualPct = v;
                    manualSeek.setProgress(v);
                    setMode(MODE_MANUAL);
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1);
            p.setMargins(i == 0 ? 0 : dp(5), 0, i == qn.length - 1 ? 0 : dp(5), 0);
            quick.addView(q, p);
        }
        col.addView(quick, lpm(-1, -2, 0, 12, 0, 0));

        manualStart = Ui.button(this, "", Ui.ACCENT, 0);
        manualStart.setTextSize(18);
        manualStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (mode == MODE_MANUAL) setMode(MODE_NONE);
                else {
                    if (manualPct == 0) { manualPct = 33; manualSeek.setProgress(33); }
                    setMode(MODE_MANUAL);
                }
            }
        });
        col.addView(manualStart, lpm(-1, dp(64), 0, 12, 0, 0));

        col.addView(buildLimitsCard(), lpm(-1, -2, 0, 14, 0, 0));
        updateManualUi();
        return scroll(col);
    }

    private View buildLimitsCard() {
        LinearLayout c = card();
        c.addView(Ui.text(this, "Межі сили", 15, Ui.TEXT, true));
        c.addView(Ui.text(this, "Діють на всі вкладки: повзунок, режими, дотик, звук. "
                + "Максимум обмежує й вбудовані режими.", 12, Ui.TEXT_DIM, false));
        final TextView minT = Ui.text(this, "", 13, Ui.TEXT, false);
        final TextView maxT = Ui.text(this, "", 13, Ui.TEXT, false);
        final SeekBar minS = Ui.seek(this, 100, minPct);
        final SeekBar maxS = Ui.seek(this, 100, maxPct);
        minT.setText("Мінімум: " + minPct + "%");
        maxT.setText("Максимум: " + maxPct + "%");
        minS.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (!user) return;
                if (p > maxPct) { maxPct = p; maxS.setProgress(p); maxT.setText("Максимум: " + p + "%"); }
                minPct = p;
                minT.setText("Мінімум: " + p + "%");
                saveLimits();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        maxS.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (!user) return;
                if (p < 1) { p = 1; sb.setProgress(1); }
                if (p < minPct) { minPct = p; minS.setProgress(p); minT.setText("Мінімум: " + p + "%"); }
                maxPct = p;
                maxT.setText("Максимум: " + p + "%");
                saveLimits();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        c.addView(minT, lpm(-2, -2, 0, 12, 0, 0));
        c.addView(minS, lpm(-1, dp(40), 0, 2, 0, 0));
        c.addView(maxT, lpm(-2, -2, 0, 8, 0, 0));
        c.addView(maxS, lpm(-1, dp(40), 0, 2, 0, 0));
        return c;
    }

    private void saveLimits() {
        prefs.edit().putInt("minPct", minPct).putInt("maxPct", maxPct).apply();
        updateManualUi();
        if (mode == MODE_BUILTIN && builtinIdx > 0) ble.setBuiltinMode(builtinIdx, effectiveBuiltinStrength());
    }

    private void updateManualUi() {
        if (manualBig == null) return;
        manualBig.setText(manualPct + "%");
        manualSub.setText("на іграшці: " + toPower(limit(manualPct)) + " з " + NeoBle.MAX_POWER
                + (minPct > 0 || maxPct < 100 ? "  (з урахуванням меж)" : ""));
        boolean on = mode == MODE_MANUAL;
        manualStart.setText(on ? "❚❚  ПАУЗА" : "▶  СТАРТ");
        Ui.bg(manualStart, on ? Ui.CARD_HI : Ui.ACCENT, on ? Ui.ACCENT : 0, 32);
    }

    // ---------------------------------------------------------------- Режими

    private View buildBuiltinPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // --- вбудовані режими іграшки
        LinearLayout bc = card();
        bc.addView(Ui.text(this, "Вбудовані режими іграшки", 15, Ui.TEXT, true));
        bc.addView(Ui.text(this, "Ті самі 7 режимів, що у фірмовому додатку Svakom", 12, Ui.TEXT_DIM, false));
        LinearLayout brow = null;
        for (int i = 1; i <= NeoBle.MODE_COUNT; i++) {
            if ((i - 1) % 4 == 0) {
                brow = new LinearLayout(this);
                bc.addView(brow, lpm(-1, -2, 0, 8, 0, 0));
            }
            final int mi = i;
            TextView b = Ui.button(this, String.valueOf(i), Ui.CARD_HI, Ui.ACCENT_DIM);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { toggleBuiltin(mi); }
            });
            builtinBtns.add(b);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(48), 1);
            bp.setMargins((i - 1) % 4 == 0 ? 0 : dp(4), 0, (i - 1) % 4 == 3 ? 0 : dp(4), 0);
            brow.addView(b, bp);
        }
        View filler = new View(this);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, dp(48), 1);
        fp.setMargins(dp(4), 0, 0, 0);
        brow.addView(filler, fp);
        final TextView bsv = Ui.text(this, "", 13, Ui.TEXT_DIM, false);
        bc.addView(bsv, lpm(-2, -2, 0, 12, 0, 0));
        builtinStrength = prefs.getInt("bstrength", NeoBle.DEFAULT_MODE_STRENGTH);
        bsv.setText("Сила вбудованого режиму: " + builtinStrength + " з 10 (у Svakom завжди 5)");
        SeekBar bs = Ui.seek(this, 9, builtinStrength - 1);
        bs.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                builtinStrength = p + 1;
                bsv.setText("Сила вбудованого режиму: " + builtinStrength + " з 10 (у Svakom завжди 5)");
                prefs.edit().putInt("bstrength", builtinStrength).apply();
                if (mode == MODE_BUILTIN && builtinIdx > 0) ble.setBuiltinMode(builtinIdx, effectiveBuiltinStrength());
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        bc.addView(bs, lpm(-1, dp(40), 0, 2, 0, 0));
        col.addView(bc, lp(-1, -2));

        TextView hint = Ui.text(this,
                "Режими працюють усередині іграшки. Натисніть ще раз на активний режим або СТОП, щоб вимкнути.",
                12, Ui.TEXT_DIM, false);
        col.addView(hint, lpm(-1, -2, 4, 12, 4, 0));
        updatePatternButtons();
        return scroll(col);
    }

    private View buildPatternsPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout c = card();
        c.addView(Ui.text(this, "Сила режиму", 14, Ui.TEXT, true));
        final TextView mv = Ui.text(this, masterPct + "%", 13, Ui.TEXT_DIM, false);
        SeekBar ms = Ui.seek(this, 100, masterPct);
        ms.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                masterPct = p;
                mv.setText(p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        c.addView(ms, lpm(-1, dp(40), 0, 4, 0, 0));
        c.addView(mv);
        col.addView(c, lp(-1, -2));

        TextView rnd = Ui.button(this, "⤮  Випадкова гра", Ui.CARD, Ui.ACCENT_DIM);
        rnd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePattern(RANDOM_IDX); }
        });
        patternBtns.add(rnd);
        patternIds.add(RANDOM_IDX);
        col.addView(rnd, lpm(-1, dp(56), 0, 12, 0, 4));

        LinearLayout row = null;
        for (int i = 0; i < Patterns.COUNT; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                col.addView(row, lpm(-1, -2, 0, 8, 0, 0));
            }
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(6), dp(10), dp(6), dp(10));
            MiniWave mw = new MiniWave(this, Patterns.preview(i, 24));
            cell.addView(mw, lp(-1, dp(26)));
            TextView label = Ui.text(this, Patterns.NAMES[i], 12, Ui.TEXT, false);
            label.setGravity(Gravity.CENTER);
            cell.addView(label, lpm(-1, -2, 0, 6, 0, 0));
            // клікабельна картка: TextView-обгортка не потрібна, слухаємо на LinearLayout
            cell.setClickable(true);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { togglePattern(idx); }
            });
            TextView holder = label; // для підсвічування
            patternBtns.add(holder);
            patternIds.add(i);
            cell.setTag(holder);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            int m = dp(4);
            p.setMargins(i % 3 == 0 ? 0 : m, 0, i % 3 == 2 ? 0 : m, 0);
            row.addView(cell, p);
        }
        // заповнити порожні місця в останньому рядку
        int rest = (3 - Patterns.COUNT % 3) % 3;
        for (int i = 0; i < rest; i++) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        updatePatternButtons();
        return scroll(col);
    }

    private final List<TextView> builtinBtns = new ArrayList<TextView>();

    private void toggleBuiltin(int idx) {
        if (mode == MODE_BUILTIN && builtinIdx == idx) {
            setMode(MODE_NONE);
            return;
        }
        setMode(MODE_BUILTIN);
        builtinIdx = idx;
        ble.setBuiltinMode(idx, effectiveBuiltinStrength());
        updatePatternButtons();
        updateService();
    }

    private void togglePattern(int idx) {
        if (mode == MODE_PATTERN && patternIdx == idx) {
            setMode(MODE_NONE);
            return;
        }
        setMode(MODE_PATTERN);
        patternIdx = idx;
        pattern = idx == RANDOM_IDX ? Patterns.random(rnd) : Patterns.get(idx);
        patPos = 0;
        updatePatternButtons();
        updateService();
    }

    private void updatePatternButtons() {
        for (int i = 0; i < builtinBtns.size(); i++) {
            boolean on = mode == MODE_BUILTIN && builtinIdx == i + 1;
            TextView b = builtinBtns.get(i);
            Ui.bg(b, on ? Ui.ACCENT : Ui.CARD_HI, on ? 0 : Ui.ACCENT_DIM, 16);
        }
        for (int i = 0; i < patternBtns.size(); i++) {
            TextView t = patternBtns.get(i);
            boolean on = mode == MODE_PATTERN && patternIdx == patternIds.get(i);
            View target = patternIds.get(i) == RANDOM_IDX ? t : (View) t.getParent();
            if (target == null) continue;
            int bgc = on ? Ui.CARD_HI : Ui.CARD;
            int stroke = on ? Ui.ACCENT : Ui.ACCENT_DIM;
            if (target instanceof TextView) Ui.bg((TextView) target, bgc, stroke, 16);
            else target.setBackground(Ui.roundStroke(this, bgc, stroke, 16));
            t.setTextColor(on ? Ui.ACCENT_LIGHT : Ui.TEXT);
        }
    }

    // ---------------------------------------------------------------- Дотик

    private View buildTouchPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        pad = new TouchPadView(this);
        col.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout row = new LinearLayout(this);
        loopBtn = Ui.button(this, "", Ui.CARD, Ui.ACCENT_DIM);
        loopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !loopOn;
                if (on && !pad.hasRecording()) {
                    toast("Спочатку намалюйте рух пальцем");
                    return;
                }
                loopOn = on;
                pad.setLoop(on);
                if (on) setMode(MODE_TOUCH);
                updateLoopBtn();
            }
        });
        row.addView(loopBtn, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView clr = Ui.button(this, "Очистити", Ui.CARD, Ui.ACCENT_DIM);
        clr.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                loopOn = false;
                pad.setLoop(false);
                pad.clearRecording();
                updateLoopBtn();
                if (mode == MODE_TOUCH) setMode(MODE_NONE);
            }
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(120), dp(52));
        p.setMargins(dp(10), 0, 0, 0);
        row.addView(clr, p);
        col.addView(row, lpm(-1, -2, 0, 10, 0, 0));
        updateLoopBtn();
        return col;
    }

    private boolean loopOn = false;

    private void updateLoopBtn() {
        if (loopBtn == null) return;
        loopBtn.setText(loopOn ? "⟳  Повтор: увімк." : "⟳  Повторювати рух");
        Ui.bg(loopBtn, loopOn ? Ui.CARD_HI : Ui.CARD, loopOn ? Ui.ACCENT : Ui.ACCENT_DIM, 16);
    }

    // ---------------------------------------------------------------- Звук

    private View buildSoundPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout src = new LinearLayout(this);
        srcMicBtn = Ui.button(this, "🎤  Мікрофон", Ui.CARD, Ui.ACCENT_DIM);
        srcMusicBtn = Ui.button(this, "🎵  Музика телефону", Ui.CARD, Ui.ACCENT_DIM);
        srcMicBtn.setTextSize(13);
        srcMusicBtn.setTextSize(13);
        srcMicBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSoundSource(false); }
        });
        srcMusicBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSoundSource(true); }
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(50), 1);
        p1.setMargins(0, 0, dp(5), 0);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(50), 1);
        p2.setMargins(dp(5), 0, 0, 0);
        src.addView(srcMicBtn, p1);
        src.addView(srcMusicBtn, p2);
        col.addView(src, lp(-1, -2));

        soundHint = Ui.text(this, "", 14, Ui.TEXT, true);
        soundHint.setGravity(Gravity.CENTER);
        col.addView(soundHint, lpm(-1, -2, 8, 14, 8, 0));

        micBtn = Ui.text(this, "", 40, Ui.TEXT, false);
        micBtn.setGravity(Gravity.CENTER);
        micBtn.setClickable(true);
        micBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMic(); }
        });
        col.addView(micBtn, lpm(dp(140), dp(140), 0, 18, 0, 0));

        micLevel = Ui.text(this, "", 14, Ui.TEXT_DIM, false);
        micLevel.setGravity(Gravity.CENTER);
        col.addView(micLevel, lpm(-1, -2, 0, 12, 0, 0));

        LinearLayout c = card();
        sensTitle = Ui.text(this, "Чутливість", 15, Ui.TEXT, true);
        c.addView(sensTitle);
        int sens = prefs.getInt("sens", 60);
        sound.setSensitivity(sens / 100f);
        music.setSensitivity(sens / 100f);
        SeekBar s = Ui.seek(this, 100, sens);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                sound.setSensitivity(p / 100f);
                music.setSensitivity(p / 100f);
                prefs.edit().putInt("sens", p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        c.addView(s, lpm(-1, dp(44), 0, 6, 0, 0));
        LinearLayout scale = new LinearLayout(this);
        TextView a = Ui.text(this, "Низька", 12, Ui.TEXT_DIM, false);
        TextView b = Ui.text(this, "Висока", 12, Ui.TEXT_DIM, false);
        b.setGravity(Gravity.END);
        scale.addView(a, new LinearLayout.LayoutParams(0, -2, 1));
        scale.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(scale);
        col.addView(c, lpm(-1, -2, 0, 20, 0, 0));
        updateMicBtn();
        return scroll(col);
    }

    private void setSoundSource(boolean musicSrc) {
        if (musicSrc == musicSource) return;
        boolean wasOn = mode == MODE_SOUND;
        if (wasOn) setMode(MODE_NONE);
        musicSource = musicSrc;
        prefs.edit().putBoolean("musicSrc", musicSrc).apply();
        updateMicBtn();
        if (wasOn) toggleMic();
    }

    private void toggleMic() {
        if (mode == MODE_SOUND) {
            setMode(MODE_NONE);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterPerms = new Runnable() { @Override public void run() { toggleMic(); } };
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        boolean ok = musicSource ? music.start() : sound.start();
        if (!ok) {
            toast(musicSource ? "Цей телефон не дає слухати музику з інших додатків" : "Не вдалося увімкнути мікрофон");
            return;
        }
        setMode(MODE_SOUND);
    }

    private void updateMicBtn() {
        if (micBtn == null) return;
        boolean on = mode == MODE_SOUND;
        micBtn.setText(musicSource ? (on ? "🎶" : "🎵") : (on ? "🎙" : "🎤"));
        micBtn.setBackground(Ui.roundStroke(this, on ? Ui.CARD_HI : Ui.CARD, on ? Ui.ACCENT : Ui.ACCENT_DIM, 70));
        micLevel.setText(on ? (musicSource ? "Слухаю музику…" : "Слухаю…") : "Натисніть, щоб увімкнути");
        soundHint.setText(musicSource
                ? "Іграшка рухається в такт музиці, що грає на телефоні: Spotify, YouTube, будь-який плеєр."
                : "Гучність навколишнього звуку (музика, голос) керує силою і ритмом іграшки.");
        sensTitle.setText(musicSource ? "Чутливість до музики" : "Чутливість мікрофона");
        Ui.bg(srcMicBtn, !musicSource ? Ui.CARD_HI : Ui.CARD, !musicSource ? Ui.ACCENT : Ui.ACCENT_DIM, 16);
        Ui.bg(srcMusicBtn, musicSource ? Ui.CARD_HI : Ui.CARD, musicSource ? Ui.ACCENT : Ui.ACCENT_DIM, 16);
        srcMicBtn.setTextColor(!musicSource ? Ui.ACCENT_LIGHT : Ui.TEXT);
        srcMusicBtn.setTextColor(musicSource ? Ui.ACCENT_LIGHT : Ui.TEXT);
    }

    // ---------------------------------------------------------------- Відео

    private View buildVideoPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        videoBox = new FrameLayout(this);
        videoBox.setBackground(Ui.round(this, 0xFF000000, 16));
        videoBox.setClipToOutline(true);
        video = new VideoView(this);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        videoBox.addView(video, vp);
        videoPlaceholder = Ui.text(this, "Оберіть відео 👇", 15, Ui.TEXT_DIM, true);
        videoPlaceholder.setGravity(Gravity.CENTER);
        videoBox.addView(videoPlaceholder, new FrameLayout.LayoutParams(-1, -1));
        // кнопки поверх відео в повноекранному режимі
        LinearLayout ov = new LinearLayout(this);
        ov.setGravity(Gravity.END);
        TextView ovStop = Ui.button(this, "■", Ui.RED, 0);
        ovStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopAll(); }
        });
        TextView ovExit = Ui.button(this, "✕", 0xAA000000, 0);
        ovExit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setFullscreen(false); }
        });
        ov.addView(ovStop, lpm(dp(48), dp(48), 0, 0, 8, 0));
        ov.addView(ovExit, lpm(dp(48), dp(48), 0, 0, 0, 0));
        ov.setVisibility(View.GONE);
        fsOverlay = ov;
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        op.setMargins(dp(12), dp(12), dp(12), dp(12));
        videoBox.addView(ov, op);

        MediaController mc = new MediaController(this);
        mc.setAnchorView(videoBox);
        video.setMediaController(mc);
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                videoReady = true;
                videoPlaceholder.setVisibility(View.GONE);
                video.seekTo(1); // показати перший кадр
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer mp) { if (mode == MODE_VIDEO) setMode(MODE_NONE); }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                videoPlaceholder.setText("Не вдалося відкрити відео");
                videoPlaceholder.setVisibility(View.VISIBLE);
                return true;
            }
        });
        page.addView(videoBox, new LinearLayout.LayoutParams(-1, dp(210)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout files = card();
        LinearLayout pick = new LinearLayout(this);
        TextView pv = Ui.button(this, "📂  Відео", Ui.ACCENT, 0);
        pv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(REQ_PICK_VIDEO, "video/*"); }
        });
        TextView ps = Ui.button(this, "📜  Скрипт", Ui.CARD_HI, Ui.ACCENT_DIM);
        ps.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(REQ_PICK_SCRIPT, "*/*"); }
        });
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, dp(48), 1);
        a1.setMargins(0, 0, dp(5), 0);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, dp(48), 1);
        a2.setMargins(dp(5), 0, 0, 0);
        pick.addView(pv, a1);
        pick.addView(ps, a2);
        files.addView(pick);
        videoName = Ui.text(this, "Відео не обрано", 12, Ui.TEXT_DIM, false);
        scriptName = Ui.text(this, "Скрипт (.funscript) не обрано: синхронізація за звуком відео", 12, Ui.TEXT_DIM, false);
        files.addView(videoName, lpm(-1, -2, 0, 10, 0, 0));
        files.addView(scriptName, lpm(-1, -2, 0, 4, 0, 0));
        col.addView(files, lpm(-1, -2, 0, 10, 0, 0));

        LinearLayout sync = card();
        sync.addView(Ui.text(this, "Синхронізація", 15, Ui.TEXT, true));
        LinearLayout srow = new LinearLayout(this);
        syncScriptBtn = Ui.button(this, "За скриптом", Ui.CARD, Ui.ACCENT_DIM);
        syncAudioBtn = Ui.button(this, "За звуком відео", Ui.CARD, Ui.ACCENT_DIM);
        syncScriptBtn.setTextSize(13);
        syncAudioBtn.setTextSize(13);
        syncScriptBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (script == null) { toast("Спочатку оберіть файл .funscript"); return; }
                setSyncByScript(true);
            }
        });
        syncAudioBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSyncByScript(false); }
        });
        srow.addView(syncScriptBtn, new LinearLayout.LayoutParams(0, dp(46), 1));
        srow.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        srow.addView(syncAudioBtn, new LinearLayout.LayoutParams(0, dp(46), 1));
        sync.addView(srow, lpm(-1, -2, 0, 8, 0, 0));

        sync.addView(Ui.text(this, "Сила за скриптом береться від:", 12, Ui.TEXT_DIM, false), lpm(-2, -2, 0, 12, 0, 0));
        LinearLayout brow = new LinearLayout(this);
        bySpeedBtn = Ui.button(this, "Швидкості рухів", Ui.CARD, Ui.ACCENT_DIM);
        byPosBtn = Ui.button(this, "Позиції", Ui.CARD, Ui.ACCENT_DIM);
        bySpeedBtn.setTextSize(13);
        byPosBtn.setTextSize(13);
        bySpeedBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                scriptBySpeed = true;
                prefs.edit().putBoolean("bySpeed", true).apply();
                updateVideoUi();
            }
        });
        byPosBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                scriptBySpeed = false;
                prefs.edit().putBoolean("bySpeed", false).apply();
                updateVideoUi();
            }
        });
        brow.addView(bySpeedBtn, new LinearLayout.LayoutParams(0, dp(42), 1));
        brow.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        brow.addView(byPosBtn, new LinearLayout.LayoutParams(0, dp(42), 1));
        sync.addView(brow, lpm(-1, -2, 0, 6, 0, 0));

        offsetMs = prefs.getInt("offsetMs", 100);
        scriptBySpeed = prefs.getBoolean("bySpeed", true);
        offsetT = Ui.text(this, "", 13, Ui.TEXT, false);
        sync.addView(offsetT, lpm(-2, -2, 0, 14, 0, 0));
        SeekBar os = Ui.seek(this, 100, (offsetMs + 500) / 10);
        os.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (!user) return;
                offsetMs = p * 10 - 500;
                prefs.edit().putInt("offsetMs", offsetMs).apply();
                updateVideoUi();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        sync.addView(os, lpm(-1, dp(40), 0, 2, 0, 0));
        sync.addView(Ui.text(this, "Якщо іграшка запізнюється від відео, посуньте вправо.", 11, Ui.TEXT_DIM, false));
        col.addView(sync, lpm(-1, -2, 0, 10, 0, 0));

        TextView fs = Ui.button(this, "⛶  На весь екран", Ui.CARD, Ui.ACCENT_DIM);
        fs.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!videoReady) { toast("Спочатку оберіть відео"); return; }
                setFullscreen(true);
            }
        });
        col.addView(fs, lpm(-1, dp(50), 0, 10, 0, 0));

        col.addView(Ui.text(this, "Відео з YouTube чи браузера: вкладка «Звук» → «Музика телефону».",
                11, Ui.TEXT_DIM, false), lpm(-1, -2, 4, 10, 4, 6));

        ScrollView sc = scroll(col);
        videoControls = sc;
        page.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        updateVideoUi();
        return page;
    }

    private void pickFile(int req, String mime) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            toast("Не знайдено менеджер файлів");
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try {
            Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) name = c.getString(0);
                c.close();
            }
        } catch (Exception ignored) { }
        return name != null ? name : uri.getLastPathSegment();
    }

    private void keepAccess(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
    }

    private void openVideo(Uri uri, boolean quiet) {
        try {
            if (mode == MODE_VIDEO) setMode(MODE_NONE);
            videoReady = false;
            videoPlaceholder.setText("Завантаження…");
            videoPlaceholder.setVisibility(View.VISIBLE);
            video.setVideoURI(uri);
            videoName.setText("🎬  " + displayName(uri));
            prefs.edit().putString("videoUri", uri.toString()).apply();
        } catch (Exception e) {
            if (!quiet) toast("Не вдалося відкрити відео");
        }
    }

    private boolean openScript(Uri uri, boolean quiet) {
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("немає доступу");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            String name = displayName(uri);
            script = Funscript.parse(out.toString("UTF-8"), name);
            int sec = script.durationMs() / 1000;
            scriptName.setText("📜  " + name + "  ·  " + script.count() + " рухів, "
                    + (sec / 60) + ":" + String.format("%02d", sec % 60));
            prefs.edit().putString("scriptUri", uri.toString()).apply();
            setSyncByScript(true);
            return true;
        } catch (Exception e) {
            if (!quiet) toast("Це не схоже на .funscript: " + e.getMessage());
            return false;
        }
    }

    private void restoreVideo() {
        String v = prefs.getString("videoUri", null);
        String sc = prefs.getString("scriptUri", null);
        if (v != null) openVideo(Uri.parse(v), true);
        if (sc != null && !openScript(Uri.parse(sc), true)) prefs.edit().remove("scriptUri").apply();
    }

    private void setSyncByScript(boolean byScript) {
        if (!byScript && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterPerms = new Runnable() { @Override public void run() { setSyncByScript(false); } };
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_VIDEO);
            return;
        }
        syncByScript = byScript;
        if (mode == MODE_VIDEO) {
            videoMeter.stop();
            startVideoAudioIfNeeded();
            updateService();
        }
        updateVideoUi();
    }

    private void startVideoAudioIfNeeded() {
        if (syncByScript && script != null) return;
        if (video == null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Для синхронізації за звуком дозвольте «Мікрофон» (кнопка «За звуком відео»)");
            return;
        }
        int session = video.getAudioSessionId();
        if (!videoMeter.start(session) && !videoMeter.start(0)) toast("Цей телефон не дає аналізувати звук відео");
    }

    private void updateVideoUi() {
        if (syncScriptBtn == null) return;
        boolean byScript = syncByScript && script != null;
        Ui.bg(syncScriptBtn, byScript ? Ui.CARD_HI : Ui.CARD, byScript ? Ui.ACCENT : Ui.ACCENT_DIM, 14);
        Ui.bg(syncAudioBtn, !byScript ? Ui.CARD_HI : Ui.CARD, !byScript ? Ui.ACCENT : Ui.ACCENT_DIM, 14);
        syncScriptBtn.setTextColor(byScript ? Ui.ACCENT_LIGHT : (script == null ? Ui.TEXT_DIM : Ui.TEXT));
        syncAudioBtn.setTextColor(!byScript ? Ui.ACCENT_LIGHT : Ui.TEXT);
        Ui.bg(bySpeedBtn, scriptBySpeed ? Ui.CARD_HI : Ui.CARD, scriptBySpeed ? Ui.ACCENT : Ui.ACCENT_DIM, 14);
        Ui.bg(byPosBtn, !scriptBySpeed ? Ui.CARD_HI : Ui.CARD, !scriptBySpeed ? Ui.ACCENT : Ui.ACCENT_DIM, 14);
        bySpeedBtn.setTextColor(scriptBySpeed ? Ui.ACCENT_LIGHT : Ui.TEXT);
        byPosBtn.setTextColor(!scriptBySpeed ? Ui.ACCENT_LIGHT : Ui.TEXT);
        offsetT.setText("Випередження іграшки: " + (offsetMs > 0 ? "+" : "") + offsetMs + " мс");
    }

    @SuppressWarnings("deprecation")
    private void setFullscreen(boolean on) {
        fullscreen = on;
        int v = on ? View.GONE : View.VISIBLE;
        headerView.setVisibility(v);
        wave.setVisibility(v);
        tabRowView.setVisibility(v);
        stopBtnView.setVisibility(v);
        videoControls.setVisibility(v);
        fsOverlay.setVisibility(on ? View.VISIBLE : View.GONE);
        rootView.setPadding(on ? 0 : dp(16), on ? 0 : dp(12), on ? 0 : dp(16), on ? 0 : dp(12));
        videoBox.setLayoutParams(on ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(-1, dp(210)));
        videoBox.setBackground(on ? null : Ui.round(this, 0xFF000000, 16));
        rootView.setBackgroundColor(on ? 0xFF000000 : Ui.BG);
        setRequestedOrientation(on ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().getDecorView().setSystemUiVisibility(on
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : 0);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (fullscreen) { setFullscreen(false); return; }
        super.onBackPressed();
    }

    // ================================================================ Bluetooth

    private String[] blePerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean has(String[] perms) {
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void tryAutoConnectSaved() {
        String addr = prefs.getString("addr", null);
        if (addr == null || !has(blePerms())) return;
        BluetoothAdapter a = ble.adapter();
        if (a == null || !a.isEnabled()) return;
        BluetoothDevice d = ble.remoteDevice(addr);
        if (d != null) {
            log("Підключаюсь до збереженої іграшки " + addr);
            ble.connect(d);
        }
    }

    private void onConnectClicked() {
        int s = ble.getState();
        if (s == NeoBle.ST_READY || s == NeoBle.ST_CONNECTING) {
            stopAll();
            ble.disconnect();
            return;
        }
        if (!has(blePerms())) {
            pendingAfterPerms = new Runnable() { @Override public void run() { onConnectClicked(); } };
            requestPermissions(blePerms(), REQ_BLE);
            return;
        }
        BluetoothAdapter a = ble.adapter();
        if (a == null) { toast("На цьому телефоні немає Bluetooth"); return; }
        if (!a.isEnabled()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT_ON);
            } catch (Exception e) {
                toast("Увімкніть Bluetooth у налаштуваннях");
            }
            return;
        }
        openPicker();
    }

    private TextView pickerHint;

    private void openPicker() {
        pickerDevs.clear();
        pickerAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        autoConnect = true;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        pickerHint = new TextView(this);
        pickerHint.setTextSize(14);
        pickerHint.setText(Build.VERSION.SDK_INT < 31
                ? "Увімкніть іграшку. На Android 11 і старших також має бути увімкнена геолокація."
                : "Увімкніть іграшку і тримайте її поруч із телефоном.");
        box.addView(pickerHint);
        ListView list = new ListView(this);
        list.setAdapter(pickerAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View v, int which, long id) {
                autoConnect = false;
                if (picker != null) picker.dismiss();
                if (which >= 0 && which < pickerDevs.size()) ble.connect(pickerDevs.get(which));
            }
        });
        box.addView(list, new LinearLayout.LayoutParams(-1, dp(260)));
        picker = new AlertDialog.Builder(this)
                .setTitle("Шукаю Alex Neo 2…")
                .setView(box)
                .setNegativeButton("Скасувати", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        autoConnect = false;
                        ble.stopScan();
                        if (ble.getState() == NeoBle.ST_SCANNING) onState(NeoBle.ST_IDLE, "Не підключено");
                    }
                })
                .create();
        picker.show();
        if (!ble.startScan()) picker.dismiss();
    }

    @Override public void onFound(BluetoothDevice dev, String name, int rssi) {
        if (picker == null || !picker.isShowing()) return;
        if (autoConnect && NeoBle.looksLikeAlex(name)) {
            autoConnect = false;
            picker.dismiss();
            ble.connect(dev);
            return;
        }
        pickerDevs.add(dev);
        pickerAdapter.add(name + "   (" + rssi + " dBm)");
        pickerHint.setText("Alex Neo 2 не видно серед знайдених? Оберіть пристрій вручну:");
        picker.setTitle("Оберіть іграшку");
    }

    @Override public void onBattery(int percent) {
        batteryPct = percent;
        refreshStatus();
    }

    private void refreshStatus() {
        String t = statusBase;
        if (ble.isReady() && batteryPct > 0) t += "  ·  🔋 " + batteryPct + "%";
        statusText.setText(t);
    }

    @Override public void onState(int state, String text) {
        statusBase = text;
        if (state != NeoBle.ST_READY) batteryPct = -1;
        refreshStatus();
        int color;
        String btn;
        switch (state) {
            case NeoBle.ST_READY: color = Ui.GREEN; btn = "Відключити"; break;
            case NeoBle.ST_CONNECTING: color = Ui.ORANGE; btn = "Скасувати"; break;
            case NeoBle.ST_SCANNING: color = Ui.ORANGE; btn = "Підключити"; break;
            default: color = Ui.RED; btn = "Підключити"; break;
        }
        statusDot.setBackground(Ui.round(this, color, 5));
        connectBtn.setText(btn);
        Ui.bg(connectBtn, state == NeoBle.ST_READY ? Ui.CARD_HI : Ui.ACCENT,
                state == NeoBle.ST_READY ? Ui.ACCENT_DIM : 0, 16);
        updateService();
        if (state == NeoBle.ST_READY && ble.deviceAddress() != null) {
            prefs.edit().putString("addr", ble.deviceAddress()).apply();
            toast(text);
        }
        if (state == NeoBle.ST_IDLE && picker != null && picker.isShowing() && pickerDevs.isEmpty()) {
            picker.setTitle("Нічого не знайдено");
            pickerHint.setText(text);
        }
        if (state == NeoBle.ST_IDLE && mode != MODE_NONE && ble.deviceAddress() != null) {
            // зв'язок втрачено: зупиняємо режими, щоб після перепідключення нічого не ввімкнулось раптово
            setMode(MODE_NONE);
            ble.setPower(0);
        }
    }

    @Override public void onLog(String line) { android.util.Log.d("NeoRemote", line); }

    private void log(String line) { onLog(line); }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        boolean ok = results.length > 0;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        Runnable next = pendingAfterPerms;
        pendingAfterPerms = null;
        if (ok && next != null) {
            next.run();
        } else if (!ok && code != REQ_NOTIF) {
            toast(code == REQ_MIC || code == REQ_MIC_VIDEO
                    ? "Без доступу до мікрофона режим «Звук» не працює"
                    : "Без дозволу на Bluetooth підключитися не вийде");
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_BT_ON && result == RESULT_OK) openPicker();
        if ((code == REQ_PICK_VIDEO || code == REQ_PICK_SCRIPT) && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            keepAccess(uri);
            if (code == REQ_PICK_VIDEO) openVideo(uri, false); else openScript(uri, false);
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
