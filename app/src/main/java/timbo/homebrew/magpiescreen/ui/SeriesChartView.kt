package timbo.homebrew.magpiescreen.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import timbo.homebrew.magpiescreen.R
import java.util.Locale

/** Minimal bar chart for a usage series; bars are proportional to the largest bucket. */
class SeriesChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class Point(val label: String, val value: Double)

    /**
     * @param valueLabels print the formatted value on top of every bar
     * @param allPointLabels print every point label on the axis (dense series); otherwise only first/last
     * @param maxLabel print the "max …" caption in the top-left corner
     */
    data class Style(
        val valueLabels: Boolean = false,
        val allPointLabels: Boolean = false,
        val maxLabel: Boolean = true,
    )

    /** Called with the index of the bar under the finger. */
    var onPointTap: ((Int) -> Unit)? = null

    private var points: List<Point> = emptyList()
    private var formatter: (Double) -> String = { String.format(Locale.US, "%.0f", it) }
    private var style: Style = Style()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val dimBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.line) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.line)
        strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted)
        textSize = 18f
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted)
        textSize = 12f
    }
    private val rect = RectF()

    fun setData(points: List<Point>, style: Style = Style(), formatter: (Double) -> String) {
        this.points = points
        this.formatter = formatter
        this.style = style
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onPointTap == null) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val count = points.size
                val w = width.toFloat()
                if (count > 0 && w > 0f) {
                    val index = (event.x / (w / count)).toInt().coerceIn(0, count - 1)
                    onPointTap?.invoke(index)
                }
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val labelHeight = if (points.isEmpty()) 0f else if (style.allPointLabels) 14f else 20f
        val chartTop = when {
            style.maxLabel -> 16f
            style.valueLabels -> 15f
            else -> 4f
        }
        val chartBottom = h - labelHeight
        val available = chartBottom - chartTop
        canvas.drawLine(0f, chartBottom, w, chartBottom, linePaint)

        if (points.isEmpty()) {
            canvas.drawText("暂无数据", 4f, h / 2, textPaint)
            return
        }

        val max = points.maxOf { it.value }
        if (style.maxLabel) canvas.drawText("max ${formatter(max)}", 2f, 12f, textPaint)

        val slot = w / points.size
        val barWidth = (slot * 0.62f).coerceAtLeast(1.5f)
        points.forEachIndexed { index, point ->
            val ratio = if (max <= 0.0) 0f else (point.value / max).toFloat()
            val barHeight = available * ratio
            val left = index * slot + (slot - barWidth) / 2f
            rect.set(left, chartBottom - barHeight, left + barWidth, chartBottom)
            canvas.drawRect(rect, if (point.value > 0.0) barPaint else dimBarPaint)
            if (style.valueLabels && point.value > 0.0) {
                val text = formatter(point.value)
                smallPaint.textSize = 12f
                if (smallPaint.measureText(text) > slot * 0.94f) {
                    // Long values ("459.47K") must not run into the neighbouring bar.
                    smallPaint.textSize = (12f * slot * 0.94f / smallPaint.measureText(text)).coerceIn(8f, 12f)
                }
                val tx = left + barWidth / 2f - smallPaint.measureText(text) / 2f
                canvas.drawText(text, tx, chartBottom - barHeight - 3f, smallPaint)
            }
        }

        val labelY = h - 4f
        if (style.allPointLabels) {
            smallPaint.textSize = 12f
            points.forEachIndexed { index, point ->
                val textWidth = smallPaint.measureText(point.label)
                val x = index * slot + (slot - textWidth) / 2f
                canvas.drawText(point.label, x, labelY, smallPaint)
            }
        } else {
            canvas.drawText(points.first().label, 2f, labelY, textPaint)
            val lastWidth = textPaint.measureText(points.last().label)
            canvas.drawText(points.last().label, w - lastWidth - 2f, labelY, textPaint)
        }
    }
}
