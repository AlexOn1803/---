package ua.screentranslator

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Служба спеціальних можливостей:
 *  - коротке натискання «гучність вниз» → переклад екрана (ще раз → сховати);
 *  - утримання «вниз» зменшує гучність, «вгору» система обробляє сама;
 *  - робить знімок екрана, розпізнає текст, перекладає і показує поверх оригіналу.
 */
class ScreenTranslatorService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager

    private var translator: Translator? = null
    private var recognizer: TextRecognizer? = null

    private var overlayView: View? = null
    private var statusView: TextView? = null
    private var busy = false

    // Стан кнопки «гучність вниз»
    private var volDownHeld = false
    private var volDownRepeated = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
    }

    // Застосунок закрили — ховаємо переклад, якщо він на екрані.
    private val stateListener: (Boolean) -> Unit = { active ->
        if (!active) {
            removeOverlay()
            hideStatus()
            resetKeys()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        TranslatorState.addListener(stateListener)
        translator = Translation.getClient(TranslatorConfig.options)
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        TranslatorState.removeListener(stateListener)
        removeOverlay()
        hideStatus()
        translator?.close()
        recognizer?.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- кнопки

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Перехоплюємо лише «гучність вниз»; «вгору» повністю обробляє система.
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        // Застосунок закритий, дзвінок / рингтон або вимкнений екран — кнопку не чіпаємо.
        if (!TranslatorState.active || !powerManager.isInteractive ||
            audioManager.mode != AudioManager.MODE_NORMAL
        ) {
            resetKeys()
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    volDownHeld = true
                    volDownRepeated = false
                } else if (volDownHeld) {
                    // Кнопку утримують — зменшуємо гучність, як система.
                    volDownRepeated = true
                    adjustVolume(false)
                }
            }

            KeyEvent.ACTION_UP -> {
                // Коротке натискання (без утримання) — переклад або закрити переклад.
                if (volDownHeld && !volDownRepeated) onTranslateKey()
                resetKeys()
            }
        }
        return true
    }

    private fun resetKeys() {
        volDownHeld = false
        volDownRepeated = false
    }

    private fun adjustVolume(up: Boolean) {
        try {
            audioManager.adjustSuggestedStreamVolume(
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (e: SecurityException) {
            // Напр., перехід у режим «Не турбувати» без дозволу — просто ігноруємо.
            Log.w(TAG, "adjustVolume", e)
        }
    }

    private fun onTranslateKey() {
        if (overlayView != null) {
            removeOverlay()
            return
        }
        if (busy) return
        startTranslation()
    }

    // ---------------------------------------------------------------- конвеєр

    private fun startTranslation() {
        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = try {
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    copy
                } finally {
                    buffer.close()
                }
                if (bitmap == null) {
                    finishWithMessage(getString(R.string.screenshot_failed, -1))
                } else {
                    recognize(bitmap)
                }
            }

            override fun onFailure(errorCode: Int) {
                finishWithMessage(getString(R.string.screenshot_failed, errorCode))
            }
        })
    }

    private fun recognize(bitmap: Bitmap) {
        val recognizer = recognizer ?: return finishWithMessage(getString(R.string.ocr_failed))
        showStatus(getString(R.string.translating))
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val blocks = buildBlocks(text, bitmap)
                bitmap.recycle()
                if (blocks.isEmpty()) {
                    finishWithMessage(getString(R.string.no_text))
                } else {
                    translate(blocks)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR", e)
                bitmap.recycle()
                finishWithMessage(getString(R.string.ocr_failed))
            }
    }

    private fun buildBlocks(text: Text, bitmap: Bitmap): List<TranslatedBlock> =
        text.textBlocks.mapNotNull { block ->
            val rect = block.boundingBox ?: return@mapNotNull null
            if (rect.width() <= 0 || rect.height() <= 0) return@mapNotNull null
            val source = joinLines(block.lines.map { it.text })
            // Пропускаємо блоки без англійських літер (числа, час, символи).
            if (source.count { it in 'a'..'z' || it in 'A'..'Z' } < 2) return@mapNotNull null
            val lineHeight = block.lines
                .mapNotNull { it.boundingBox?.height() }
                .takeIf { it.isNotEmpty() }?.average()?.toFloat()
                ?: rect.height().toFloat()
            val bg = sampleBackground(bitmap, rect)
            TranslatedBlock(
                rect = Rect(rect),
                source = source,
                lineHeight = lineHeight,
                lineCount = block.lines.size,
                bgColor = bg,
                textColor = if (luminance(bg) > 0.55f) Color.BLACK else Color.WHITE
            )
        }

    private fun translate(blocks: List<TranslatedBlock>) {
        val translator = translator ?: return finishWithMessage(getString(R.string.model_needed))
        TranslatorConfig.isModelDownloaded().addOnCompleteListener { check ->
            if (check.isSuccessful && check.result != true) {
                showStatus(getString(R.string.downloading_model))
            }
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    val tasks = blocks.map { translator.translate(it.source) }
                    Tasks.whenAllComplete(tasks).addOnCompleteListener {
                        val done = blocks.mapIndexedNotNull { i, b ->
                            val t = tasks[i]
                            if (t.isSuccessful && !t.result.isNullOrBlank()) b.copy(translated = t.result) else null
                        }
                        hideStatus()
                        busy = false
                        if (done.isEmpty()) toast(getString(R.string.no_text)) else showOverlay(done)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "model download", e)
                    finishWithMessage(getString(R.string.model_needed))
                }
        }
    }

    private fun finishWithMessage(message: String) {
        hideStatus()
        busy = false
        toast(message)
    }

    // ---------------------------------------------------------------- вікна

    private fun showOverlay(blocks: List<TranslatedBlock>) {
        removeOverlay()
        val view = TranslationOverlayView(
            context = this,
            blocks = blocks,
            hint = getString(R.string.overlay_hint),
            onClose = { removeOverlay() },
            onCopy = { copyToClipboard(blocks) }
        )
        val bounds = windowManager.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
        windowManager.addView(view, params)
        overlayView = view
    }

    private fun removeOverlay() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
    }

    private fun showStatus(message: String) {
        statusView?.let {
            it.text = message
            return
        }
        val dp = resources.displayMetrics.density
        val view = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 24 * dp
                setColor(0xE0202124.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * dp).toInt()
        }
        windowManager.addView(view, params)
        statusView = view
    }

    private fun hideStatus() {
        statusView?.let { runCatching { windowManager.removeView(it) } }
        statusView = null
    }

    private fun copyToClipboard(blocks: List<TranslatedBlock>) {
        val text = blocks.joinToString("\n\n") { it.translated }
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        toast(getString(R.string.copied))
    }

    private fun toast(message: String) {
        handler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- утиліти

    companion object {
        private const val TAG = "ScreenTranslator"

        /** Склеює рядки блоку в одне речення; переноси «exam-\nple» зшиває. */
        fun joinLines(lines: List<String>): String {
            val sb = StringBuilder()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (sb.isNotEmpty()) {
                    if (sb.last() == '-' && sb.length > 1 && sb[sb.length - 2].isLetter() &&
                        line.first().isLowerCase()
                    ) {
                        sb.setLength(sb.length - 1)
                    } else {
                        sb.append(' ')
                    }
                }
                sb.append(line)
            }
            return sb.toString()
        }

        /** Середній колір пікселів по периметру блоку — це і є фон під текстом. */
        fun sampleBackground(bitmap: Bitmap, r: Rect): Int {
            val pad = 3
            val left = (r.left - pad).coerceIn(0, bitmap.width - 1)
            val right = (r.right + pad).coerceIn(0, bitmap.width - 1)
            val top = (r.top - pad).coerceIn(0, bitmap.height - 1)
            val bottom = (r.bottom + pad).coerceIn(0, bitmap.height - 1)
            val points = ArrayList<Pair<Int, Int>>()
            val steps = 8
            for (i in 0..steps) {
                val x = left + (right - left) * i / steps
                val y = top + (bottom - top) * i / steps
                points += x to top
                points += x to bottom
                points += left to y
                points += right to y
            }
            var rs = 0L
            var gs = 0L
            var bs = 0L
            for ((x, y) in points) {
                val c = bitmap.getPixel(x, y)
                rs += Color.red(c)
                gs += Color.green(c)
                bs += Color.blue(c)
            }
            val n = points.size
            return Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
        }

        fun luminance(c: Int): Float =
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
    }
}

data class TranslatedBlock(
    val rect: Rect,
    val source: String,
    val lineHeight: Float,
    val lineCount: Int,
    val bgColor: Int,
    val textColor: Int,
    val translated: String = ""
)
