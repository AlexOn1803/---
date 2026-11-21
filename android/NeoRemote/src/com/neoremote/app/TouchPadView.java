package com.neoremote.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Режим «Дотик»: висота пальця = інтенсивність (вгорі 100 %, внизу 0 %).
 * Намальований рух можна повторювати по колу.
 */
public class TouchPadView extends View {

    private static final int TRAIL = 60;
    private static final long TRAIL_MS = 1200;

    private final float[] tx = new float[TRAIL];
    private final float[] ty = new float[TRAIL];
    private final long[] tt = new long[TRAIL];
    private int tHead = 0, tCount = 0;

    private boolean down = false;
    private float curPct = 0;
    private boolean loop = false;
    private final List<Integer> rec = new ArrayList<Integer>();
    private boolean recording = false;
    private int playIdx = 0;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;

    public TouchPadView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        bg.setColor(Ui.CARD);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * d);
        border.setColor(Ui.ACCENT_DIM);
        grid.setColor(0x14FFFFFF);
        trail.setStrokeCap(Paint.Cap.ROUND);
        trail.setColor(Ui.ACCENT);
        label.setColor(0x99FFFFFF);
        label.setTextSize(12 * d);
        label.setTextAlign(Paint.Align.CENTER);
        hint.setColor(0xCCFFFFFF);
        hint.setTextSize(15 * d);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setFakeBoldText(true);
    }

    public void setLoop(boolean on) { loop = on; playIdx = 0; }
    public boolean isTouching() { return down; }
    public boolean hasRecording() { return rec.size() > 3; }
    public void clearRecording() { rec.clear(); playIdx = 0; }

    /** Викликається рушієм кожні 50 мс: повертає поточну інтенсивність 0..100. */
    public int sample() {
        if (down) {
            if (recording && rec.size() < 20 * 60 * 2) rec.add(Math.round(curPct));
            return Math.round(curPct);
        }
        if (loop && rec.size() > 3) {
            int v = rec.get(playIdx % rec.size());
            playIdx++;
            return v;
        }
        return 0;
    }

    private float padRight() { return getWidth() - 44 * d; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float top = 16 * d, bottom = getHeight() - 16 * d;
        float y = Math.max(top, Math.min(bottom, e.getY()));
        float x = Math.max(8 * d, Math.min(padRight() - 8 * d, e.getX()));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = true;
                recording = true;
                rec.clear();
                playIdx = 0;
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                down = false;
                recording = false;
                break;
            default:
                break;
        }
        curPct = 100f * (bottom - y) / (bottom - top);
        addTrail(x, y);
        invalidate();
        return true;
    }

    private void addTrail(float x, float y) {
        tx[tHead] = x; ty[tHead] = y; tt[tHead] = SystemClock.uptimeMillis();
        tHead = (tHead + 1) % TRAIL;
        if (tCount < TRAIL) tCount++;
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        r.set(d, d, w - d, h - d);
        c.drawRoundRect(r, 22 * d, 22 * d, bg);
        float step = 22 * d;
        for (float gx = step; gx < w; gx += step) c.drawLine(gx, 0, gx, h, grid);
        for (float gy = step; gy < h; gy += step) c.drawLine(0, gy, w, gy, grid);
        c.drawRoundRect(r, 22 * d, 22 * d, border);

        // шкала праворуч
        float bx = w - 22 * d, top = 40 * d, bottom = h - 40 * d;
        bar.setShader(new LinearGradient(0, top, 0, bottom, Ui.ACCENT, 0x10A070FF, Shader.TileMode.CLAMP));
        bar.setStrokeWidth(5 * d);
        bar.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(bx, top, bx, bottom, bar);
        c.drawText("100%", bx, top - 12 * d, label);
        c.drawText("0%", bx, bottom + 22 * d, label);

        // слід пальця, що згасає
        long now = SystemClock.uptimeMillis();
        boolean any = false;
        for (int i = 1; i < tCount; i++) {
            int a = (tHead - tCount + i - 1 + TRAIL) % TRAIL;
            int b = (a + 1) % TRAIL;
            long age = now - tt[b];
            if (age > TRAIL_MS) continue;
            any = true;
            float k = 1f - age / (float) TRAIL_MS;
            trail.setAlpha((int) (255 * k));
            trail.setStrokeWidth((6 + 16 * k) * d);
            c.drawLine(tx[a], ty[a], tx[b], ty[b], trail);
        }
        if (any || down) postInvalidateOnAnimation();

        if (!down && !any) {
            String msg = loop && hasRecording()
                    ? "Повторюю намальований рух…"
                    : "Ведіть пальцем: вище = сильніше";
            c.drawText(msg, (w - 44 * d) / 2f, h / 2f, hint);
        }
    }
}
