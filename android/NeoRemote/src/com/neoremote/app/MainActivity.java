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
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
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

    private static final int REQ_BLE = 1;
    private static final int REQ_MIC = 2;
    private static final int REQ_BT_ON = 3;
    private static final int RANDOM_IDX = 100;
    private static final long ENGINE_MS = 50;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final SoundMeter sound = new SoundMeter();
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
        setContentView(buildUi());
        showPage(0);
        h.post(engine);
        onState(NeoBle.ST_IDLE, "Не підключено");
        tryAutoConnectSaved();
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        sound.stop();
        ble.release();
        super.onDestroy();
    }

    // ================================================================ engine

    private final Runnable engine = new Runnable() {
        @Override public void run() {
            h.postDelayed(this, ENGINE_MS);
            tick++;
            if (pad != null && pad.isTouching() && mode != MODE_TOUCH) setMode(MODE_TOUCH);
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
                    pct = sound.value();
                    if (micLevel != null) micLevel.setText("Гучність → " + pct + "%");
                    break;
                case MODE_BUILTIN:
                    // вбудований режим працює в самій іграшці; на графіку показуємо його силу
                    wave.push(builtinStrength * 10, "Вбудований режим " + builtinIdx + ", сила " + builtinStrength);
                    return;
                default:
                    break;
            }
            int v = toPower(pct);
            ble.setPower(v);
            wave.push(pct, "Сила на іграшці: " + v + " / " + NeoBle.MAX_POWER);
        }
    };

    /** 0..100 % → 0..255 */
    static int toPower(int pct) {
        if (pct <= 0) return 0;
        return Math.max(1, Math.min(NeoBle.MAX_POWER, Math.round(pct * NeoBle.MAX_POWER / 100f)));
    }

    private void setMode(int m) {
        if (mode == MODE_SOUND && m != MODE_SOUND) {
            sound.stop();
            updateMicBtn();
        }
        if (m != MODE_PATTERN) {
            patternIdx = -1;
            pattern = null;
        }
        if (m != MODE_BUILTIN) builtinIdx = 0;
        if (m != MODE_TOUCH && loopOn) {
            loopOn = false;
            if (pad != null) pad.setLoop(false);
        }
        mode = m;
        updateLoopBtn();
        updateManualUi();
        updatePatternButtons();
        if (m == MODE_NONE) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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

        // --- графік
        wave = new WaveView(this);
        wave.setBackground(Ui.round(this, Ui.CARD, 18));
        root.addView(wave, lpm(ViewGroup.LayoutParams.MATCH_PARENT, dp(120), 0, 12, 0, 10));

        // --- вкладки (рівні частини ширини, без прокрутки)
        LinearLayout tabRow = new LinearLayout(this);
        String[] names = {"Керування", "Вбудовані", "Режими", "Дотик", "Звук"};
        float[] weights = {1.3f, 1.25f, 1.05f, 0.95f, 0.85f};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = Ui.text(this, names[i], 13, Ui.TEXT, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(dp(2), dp(9), dp(2), dp(9));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showPage(idx); }
            });
            tabs.add(t);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights[i]);
            tp.setMargins(i == 0 ? 0 : dp(3), 0, i == names.length - 1 ? 0 : dp(3), 0);
            tabRow.addView(t, tp);
        }
        root.addView(tabRow, lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10));

        // --- сторінки
        FrameLayout content = new FrameLayout(this);
        pages.add(buildManualPage());
        pages.add(buildBuiltinPage());
        pages.add(buildPatternsPage());
        pages.add(buildTouchPage());
        pages.add(buildSoundPage());
        for (View p : pages) content.addView(p, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // --- СТОП
        TextView stop = Ui.button(this, "■  СТОП", Ui.RED, 0);
        stop.setTextSize(17);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopAll(); }
        });
        root.addView(stop, lpm(-1, dp(56), 0, 10, 0, 0));
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

        TextView note = Ui.text(this,
                "Сила плавна: 0–100 % на повзунку = 0–255 на іграшці (як у режимі «Дотик» фірмового додатка).",
                12, Ui.TEXT_DIM, false);
        col.addView(note, lpm(-1, -2, 4, 12, 4, 0));
        updateManualUi();
        return scroll(col);
    }

    private void updateManualUi() {
        if (manualBig == null) return;
        manualBig.setText(manualPct + "%");
        manualSub.setText("сила " + toPower(manualPct) + " з " + NeoBle.MAX_POWER);
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
                if (mode == MODE_BUILTIN && builtinIdx > 0) ble.setBuiltinMode(builtinIdx, builtinStrength);
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
        ble.setBuiltinMode(idx, builtinStrength);
        updatePatternButtons();
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

        TextView hint = Ui.text(this,
                "Гучність навколишнього звуку (музика, голос) керує силою і ритмом іграшки.",
                14, Ui.TEXT, true);
        hint.setGravity(Gravity.CENTER);
        col.addView(hint, lpm(-1, -2, 8, 8, 8, 0));

        micBtn = Ui.text(this, "", 40, Ui.TEXT, false);
        micBtn.setGravity(Gravity.CENTER);
        micBtn.setClickable(true);
        micBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMic(); }
        });
        col.addView(micBtn, lpm(dp(150), dp(150), 0, 24, 0, 0));

        micLevel = Ui.text(this, "", 14, Ui.TEXT_DIM, false);
        micLevel.setGravity(Gravity.CENTER);
        col.addView(micLevel, lpm(-1, -2, 0, 14, 0, 0));

        LinearLayout c = card();
        c.addView(Ui.text(this, "Чутливість мікрофона", 15, Ui.TEXT, true));
        int sens = prefs.getInt("sens", 60);
        sound.setSensitivity(sens / 100f);
        SeekBar s = Ui.seek(this, 100, sens);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                sound.setSensitivity(p / 100f);
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
        col.addView(c, lpm(-1, -2, 0, 24, 0, 0));
        updateMicBtn();
        return scroll(col);
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
        if (!sound.start()) {
            toast("Не вдалося увімкнути мікрофон");
            return;
        }
        setMode(MODE_SOUND);
        updateMicBtn();
    }

    private void updateMicBtn() {
        if (micBtn == null) return;
        boolean on = mode == MODE_SOUND;
        micBtn.setText(on ? "🎙" : "🎤");
        micBtn.setBackground(Ui.roundStroke(this, on ? Ui.CARD_HI : Ui.CARD, on ? Ui.ACCENT : Ui.ACCENT_DIM, 75));
        micLevel.setText(on ? "Слухаю…" : "Натисніть, щоб увімкнути");
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
        } else if (!ok) {
            toast(code == REQ_MIC
                    ? "Без доступу до мікрофона режим «Звук» не працює"
                    : "Без дозволу на Bluetooth підключитися не вийде");
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_BT_ON && result == RESULT_OK) openPicker();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
