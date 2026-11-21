package com.neoremote.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Маленька іконка-хвиля режиму на кнопці. */
public class MiniWave extends View {
    private final int[] pts;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public MiniWave(Context c, int[] pts) {
        super(c);
        this.pts = pts;
        float d = c.getResources().getDisplayMetrics().density;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f * d);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(Ui.ACCENT_LIGHT);
    }

    public void setColor(int color) { p.setColor(color); invalidate(); }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float pad = 3 * getResources().getDisplayMetrics().density;
        path.reset();
        for (int i = 0; i < pts.length; i++) {
            float x = pad + (w - 2 * pad) * i / (float) (pts.length - 1);
            float y = h - pad - (h - 2 * pad) * pts[i] / 100f;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, p);
    }
}
