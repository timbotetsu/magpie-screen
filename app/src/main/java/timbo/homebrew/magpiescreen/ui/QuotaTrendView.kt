package timbo.homebrew.magpiescreen.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import timbo.homebrew.magpiescreen.R
import java.time.Instant
import java.time.ZoneId

/**
 * Quota trend on the axis of the longest window: dashed lines show the pace that would use a
 * window up exactly by its reset, solid lines are the remaining share actually measured.
 */
class QuotaTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class Point(val timeMillis: Long, val value: Double)

    data class Series(val name: String, val color: Int, val dashed: Boolean, val points: List<Point>)

    private var series: List<Series> = emptyList()
    private var xStart: Long = 0
    private var xEnd: Long = 1
    private var firstLabel: String = ""
    private var lastLabel: String = ""

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.line)
        strokeWidth = 1f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.line)
        alpha = 110
        strokeWidth = 1f
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.fg)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted)
        textSize = 18f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    fun setData(
        series: List<Series>,
        xStart: Long,
        xEnd: Long,
        firstLabel: String,
        lastLabel: String,
    ) {
        this.series = series.filter { it.points.size >= 2 }
        this.xStart = xStart
        this.xEnd = xEnd.coerceAtLeast(xStart + 1)
        this.firstLabel = firstLabel
        this.lastLabel = lastLabel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val labelHeight = if (firstLabel.isEmpty()) 4f else 20f
        val top = 6f
        val bottom = h - labelHeight
        if (bottom <= top) return

        fun yOf(value: Double): Float = bottom - (value.coerceIn(0.0, 100.0) / 100.0).toFloat() * (bottom - top)
        fun xOf(time: Long): Float = (time - xStart).toFloat() / (xEnd - xStart).toFloat() * w

        // Daily cells: one vertical line per local day, three horizontal quarter lines.
        val zone = ZoneId.systemDefault()
        for (i in 1..3) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        var day = Instant.ofEpochMilli(xStart).atZone(zone).toLocalDate().plusDays(1)
        var boundary = day.atStartOfDay(zone).toInstant().toEpochMilli()
        var guard = 0
        while (boundary <= xEnd && guard++ < 400) {
            val x = xOf(boundary)
            if (x >= 0f && x <= w) canvas.drawLine(x, top, x, bottom, gridPaint)
            day = day.plusDays(1)
            boundary = day.atStartOfDay(zone).toInstant().toEpochMilli()
        }

        canvas.drawLine(0f, top, w, top, axisPaint)
        canvas.drawLine(0f, bottom, w, bottom, axisPaint)

        if (series.isEmpty()) {
            canvas.drawText("暂无数据", 4f, h / 2, textPaint)
            return
        }

        series.forEach { line ->
            linePaint.color = line.color
            linePaint.pathEffect = if (line.dashed) DashPathEffect(floatArrayOf(9f, 7f), 0f) else null
            path.reset()
            line.points.forEachIndexed { index, point ->
                val x = xOf(point.timeMillis)
                val y = yOf(point.value)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
        }

        // The current moment, so "now" is readable against the window.
        val nowX = xOf(System.currentTimeMillis())
        if (nowX in 0f..w) canvas.drawLine(nowX, top, nowX, bottom, nowPaint)

        if (firstLabel.isNotEmpty()) {
            canvas.drawText(firstLabel, 2f, h - 4f, textPaint)
            val lastWidth = textPaint.measureText(lastLabel)
            canvas.drawText(lastLabel, w - lastWidth - 2f, h - 4f, textPaint)
        }
    }
}
