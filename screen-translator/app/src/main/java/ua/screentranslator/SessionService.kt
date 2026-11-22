package ua.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * «Сесія» перекладача: працює, поки застосунок відкритий (у тому числі у фоні).
 * Показує сповіщення з кнопкою «Вимкнути». Коли застосунок змахують зі списку
 * недавніх — onTaskRemoved вимикає перекладач.
 */
class SessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification, 0)
        }
        TranslatorState.setActive(true)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Застосунок закрили зі списку недавніх
        stopSession()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        TranslatorState.setActive(false)
        super.onDestroy()
    }

    private fun stopSession() {
        TranslatorState.setActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.btn_stop), stop).build()
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "ua.screentranslator.STOP"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, SessionService::class.java))
            } catch (e: IllegalStateException) {
                // Рідкісна ситуація, коли система не дозволяє старт — не падаємо.
                android.util.Log.w("ScreenTranslator", "start session", e)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }
    }
}
