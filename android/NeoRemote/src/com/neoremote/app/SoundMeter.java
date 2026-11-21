package com.neoremote.app;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/** Режим «Звук»: гучність з мікрофона → інтенсивність 0..100. */
public class SoundMeter {

    private static final int RATE = 16000;

    private volatile boolean running;
    private volatile int pct;
    private volatile float sensitivity = 0.5f; // 0..1
    private Thread thread;

    public boolean isRunning() { return running; }
    public int value() { return running ? pct : 0; }
    public void setSensitivity(float s) { sensitivity = Math.max(0f, Math.min(1f, s)); }

    @SuppressLint("MissingPermission")
    public boolean start() {
        if (running) return true;
        final int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        final AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 5 * 2));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); return false; }
            rec.startRecording();
        } catch (Exception e) {
            return false;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                short[] buf = new short[RATE / 20]; // 50 мс
                float smooth = 0;
                while (running) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    double sum = 0;
                    for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                    double rms = Math.sqrt(sum / n) / 32768.0;          // 0..1
                    // чутливість: від ×0.5 до ×20
                    double gain = 0.5 + 19.5 * sensitivity * sensitivity;
                    float raw = (float) Math.min(100.0, rms * 100.0 * gain * 4.0);
                    if (raw < 4) raw = 0; // тиша
                    // швидка атака, повільний спад
                    smooth = raw > smooth ? raw : smooth * 0.82f + raw * 0.18f;
                    pct = Math.round(smooth);
                }
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
                pct = 0;
            }
        }, "sound-meter");
        thread.start();
        return true;
    }

    public void stop() {
        running = false;
        pct = 0;
    }
}
