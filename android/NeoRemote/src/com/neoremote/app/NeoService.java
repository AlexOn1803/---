package com.neoremote.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Тримає додаток живим, коли екран вимкнений або додаток згорнутий:
 * foreground-сервіс зі сповіщенням (і кнопкою «Стоп») та wake lock.
 * Сам рушій (MainActivity.engine) далі працює в головному потоці.
 */
public class NeoService extends Service {

    static final String ACTION_STOP = "com.neoremote.app.STOP";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_MIC = "mic";
    private static final String CHANNEL = "neo_active";
    private static final int NOTIF_ID = 1;

    /** Що робити, коли натиснули «Стоп» у сповіщенні (встановлює MainActivity). */
    static volatile Runnable stopListener;

    private PowerManager.WakeLock wakeLock;

    static void update(Context c, String text, boolean mic) {
        Intent i = new Intent(c, NeoService.class)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_MIC, mic);
        try {
            c.startForegroundService(i);
        } catch (Exception ignored) {
            // напр. заборона запуску з фону: просто працюємо без сповіщення
        }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, NeoService.class)); } catch (Exception ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Runnable r = stopListener;
            if (r != null) r.run();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        boolean mic = intent != null && intent.getBooleanExtra(EXTRA_MIC, false);
        Notification n = build(text == null ? "Працює" : text);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                if (mic && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                startForeground(NOTIF_ID, n, type);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeoRemote:active");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(4 * 60 * 60 * 1000L); // не довше 4 год
        }
        return START_NOT_STICKY;
    }

    private Notification build(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Керування іграшкою",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int pf = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), pf);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, NeoService.class).setAction(ACTION_STOP), pf);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif), "■ Стоп", stop).build();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Alex Neo 2")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(stopAction)
                .build();
    }

    @Override public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
