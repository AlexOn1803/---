package com.neoremote.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/** Графік інтенсивності за останні кілька секунд (як у Touch Control). */
public class WaveView extends View {

    private final float[] buf = new float[140];
    private int head = 0;
    private float level = 0;
    private String label = "";

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path area = new Path();
    private final float d;

    public WaveView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3 * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(Ui.ACCENT);
        fill.setStyle(Paint.Style.FILL);
        dot.setColor(0xFFFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4 * d);
        ring.setColor(Ui.ACCENT);
        text.setColor(Ui.ACCENT_LIGHT);
        text.setTextSize(22 * d);
        text.setFakeBoldText(true);
        text.setTextAlign(Paint.Align.RIGHT);
        grid.setColor(0x22FFFFFF);
        grid.setStrokeWidth(1 * d);
    }

    /** value 0..100, label: що зараз надсилається на іграшку */
    public void push(float value, String devLabel) {
        // трохи згладжуємо, щоб лінія була плавною
        level = level + (value - level) * 0.45f;
        buf[head] = level;
        head = (head + 1) % buf.length;
        label = devLabel;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x66A070FF, 0x00A070FF, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float top = 34 * d, bottom = h - 8 * d;
        float right = w - 46 * d;
        for (int i = 1; i <= 3; i++) {
            float y = bottom - (bottom - top) * i / 3f;
            c.drawLine(0, y, right, y, grid);
        }
        int n = buf.length;
        path.reset();
        area.reset();
        float lastX = 0, lastY = bottom;
        for (int i = 0; i < n; i++) {
            float v = buf[(head + i) % n];
            float x = right * i / (n - 1);
            float y = bottom - (bottom - top) * v / 100f;
            if (i == 0) { path.moveTo(x, y); area.moveTo(x, bottom); area.lineTo(x, y); }
            else { path.lineTo(x, y); area.lineTo(x, y); }
            lastX = x; lastY = y;
        }
        area.lineTo(lastX, bottom);
        area.close();
        c.drawPath(area, fill);
        c.drawPath(path, line);
        c.drawLine(lastX, lastY, w, lastY, line);
        c.drawCircle(lastX, lastY, 9 * d, dot);
        c.drawCircle(lastX, lastY, 9 * d, ring);
        c.drawText(Math.round(level) + "%", w - 6 * d, 26 * d, text);
        Paint small = new Paint(text);
        small.setTextSize(12 * d);
        small.setFakeBoldText(false);
        small.setTextAlign(Paint.Align.LEFT);
        small.setColor(0xAAFFFFFF);
        c.drawText(label, 8 * d, 22 * d, small);
    }
}
