package ua.screentranslator

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Чи активний перекладач зараз. Живе лише в пам'яті процесу: якщо систему
 * вбила процес (закрили застосунок), після перезапуску він знову неактивний.
 */
object TranslatorState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    @Volatile
    var active: Boolean = false
        private set

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        main.post { listeners.forEach { it(value) } }
    }

    fun addListener(l: (Boolean) -> Unit) {
        listeners += l
    }

    fun removeListener(l: (Boolean) -> Unit) {
        listeners -= l
    }
}
