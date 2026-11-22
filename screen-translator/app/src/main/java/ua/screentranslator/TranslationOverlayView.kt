package ua.screentranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Повноекранний прозорий шар: для кожного розпізнаного блоку малює плашку кольору фону
 * і вписує туди український переклад (шрифт зменшується, щоб текст помістився).
 */
@SuppressLint("ViewConstructor")
class TranslationOverlayView(
    context: Context,
    blocks: List<TranslatedBlock>,
    private val hint: String,
    private val onClose: () -> Unit,
    private val onCopy: () -> Unit
) : View(context) {

    private class Placed(
        val box: RectF,
        val layout: StaticLayout,
        val textTop: Float,
        val bg: Paint
    )

    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val placed: List<Placed> = blocks.map(::place)
    private val screenLoc = IntArray(2)

    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD0202124.toInt() }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onClose()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onCopy()
        }
    })

    private fun place(b: TranslatedBlock): Placed {
        val pad = 2 * density
        val width = b.rect.width().coerceAtLeast(1)
        val maxHeight = b.rect.height().toFloat()
        val minSize = 8 * scaledDensity
        // Стартуємо з розміру, близького до оригінального шрифту.
        var size = (b.lineHeight * 0.78f).coerceIn(minSize, 40 * scaledDensity)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = b.textColor }
        var layout: StaticLayout
        while (true) {
            paint.textSize = size
            layout = StaticLayout.Builder
                .obtain(b.translated, 0, b.translated.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build()
            if (layout.height <= maxHeight || size <= minSize) break
            size = (size * 0.92f).coerceAtLeast(minSize)
        }

        val textHeight = layout.height.toFloat()
        val boxHeight = maxOf(maxHeight, textHeight)
        val box = RectF(
            b.rect.left - pad,
            b.rect.top - pad,
            b.rect.right + pad,
            b.rect.top + boxHeight + pad
        )
        // Якщо текст нижчий за блок — центруємо по вертикалі.
        val textTop = b.rect.top + (boxHeight - textHeight) / 2f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = b.bgColor }
        return Placed(box, layout, textTop, bg)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Координати OCR — екранні; зсуваємо, якщо вікно почалося не з (0,0).
        getLocationOnScreen(screenLoc)
        canvas.save()
        canvas.translate(-screenLoc[0].toFloat(), -screenLoc[1].toFloat())
        val radius = 4 * density
        for (p in placed) {
            canvas.drawRoundRect(p.box, radius, radius, p.bg)
            canvas.save()
            canvas.translate(p.box.left + 2 * density, p.textTop)
            p.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()

        // Підказка внизу екрана
        val textWidth = hintPaint.measureText(hint)
        val cx = width / 2f
        val cy = height - 72 * density
        val h = 34 * density
        val w = textWidth + 32 * density
        canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2, h / 2, hintBg)
        val fm = hintPaint.fontMetrics
        canvas.drawText(hint, cx, cy - (fm.ascent + fm.descent) / 2, hintPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)
}
