package com.neoremote.app;

import android.media.audiofx.Visualizer;

/**
 * Режим «Музика»: слухає звук, що грає на самому телефоні (будь-який плеєр),
 * через Android Visualizer на загальному виході (сесія 0). Мікрофон не потрібен,
 * але Android вимагає дозвіл RECORD_AUDIO для Visualizer.
 */
public class MusicMeter {

    private Visualizer vis;
    private volatile int pct;
    private volatile float sensitivity = 0.5f;
    private float smooth, avg;

    public boolean isRunning() { return vis != null; }
    public int value() { return vis != null ? pct : 0; }
    public void setSensitivity(float s) { sensitivity = Math.max(0f, Math.min(1f, s)); }

    public boolean start() {
        if (vis != null) return true;
        try {
            Visualizer v = new Visualizer(0);
            v.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.max(range[0], Math.min(1024, range[1])));
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            int rate = Math.min(Visualizer.getMaxCaptureRate(), 20000); // ≤ 20 разів/с
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer vz, byte[] wave, int samplingRate) {
                    process(wave);
                }
                @Override public void onFftDataCapture(Visualizer vz, byte[] fft, int samplingRate) { }
            }, rate, true, false);
            if (v.setEnabled(true) != Visualizer.SUCCESS) {
                v.release();
                return false;
            }
            smooth = 0;
            avg = 0;
            vis = v;
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void process(byte[] w) {
        if (w == null || w.length == 0) return;
        double sum = 0;
        for (byte b : w) {
            int s = (b & 0xFF) - 128;
            sum += s * s;
        }
        double rms = Math.sqrt(sum / w.length) / 128.0; // 0..1
        double gain = 0.5 + 9.5 * sensitivity * sensitivity;
        float energy = (float) Math.min(100.0, rms * 100.0 * gain * 2.0);
        // повільне середнє: різкі удари (біти) вище за нього підсилюємо
        avg = avg * 0.94f + energy * 0.06f;
        float beat = Math.max(0f, energy - avg) * 1.6f;
        float raw = Math.min(100f, energy * 0.6f + beat);
        if (raw < 3) raw = 0;
        smooth = raw > smooth ? raw : smooth * 0.7f + raw * 0.3f;
        pct = Math.round(smooth);
    }

    public void stop() {
        Visualizer v = vis;
        vis = null;
        pct = 0;
        if (v != null) {
            try { v.setEnabled(false); } catch (Throwable ignored) { }
            try { v.release(); } catch (Throwable ignored) { }
        }
    }
}
