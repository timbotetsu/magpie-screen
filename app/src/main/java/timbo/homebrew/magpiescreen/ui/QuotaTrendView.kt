package timbo.homebrew.magpiescreen.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
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
    private var fromStart: Long = 0
    private var fromEnd: Long = 1
    private var progress = 1f
    private var animator: ValueAnimator? = null
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
        animate: Boolean = false,
    ) {
        val nextEnd = xEnd.coerceAtLeast(xStart + 1)
        // Read the current interpolated axis before cancel() snaps the old animator to its end.
        val baseStart = currentStart()
        val baseEnd = currentEnd()
        animator?.cancel()
        animator = null

        val canAnimate = animate && isAttachedToWindow && this.xEnd - this.xStart > 1_000L
        if (canAnimate) {
            fromStart = baseStart
            fromEnd = baseEnd
            progress = 0f
        } else {
            fromStart = xStart
            fromEnd = nextEnd
            progress = 1f
        }
        this.series = series.filter { it.points.size >= 2 }
        this.xStart = xStart
        this.xEnd = nextEnd
        this.firstLabel = firstLabel
        this.lastLabel = lastLabel

        if (canAnimate) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        progress = 1f
                        animator = null
                        invalidate()
                    }
                })
                start()
            }
        } else {
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun currentStart(): Long =
        if (progress >= 1f) xStart
        else (fromStart + (xStart - fromStart).toDouble() * progress).toLong()

    private fun currentEnd(): Long =
        if (progress >= 1f) xEnd
        else (fromEnd + (xEnd - fromEnd).toDouble() * progress).toLong()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val labelHeight = if (firstLabel.isEmpty()) 4f else 20f
        val top = 6f
        val bottom = h - labelHeight
        if (bottom <= top) return

        val start = currentStart()
        val end = currentEnd().coerceAtLeast(start + 1)
        fun yOf(value: Double): Float = bottom - (value.coerceIn(0.0, 100.0) / 100.0).toFloat() * (bottom - top)
        fun xOf(time: Long): Float =
            ((time - start).toDouble() / (end - start).toDouble() * w).toFloat()

        // Daily cells: one vertical line per local day, three horizontal quarter lines.
        val zone = ZoneId.systemDefault()
        for (i in 1..3) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        var day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().plusDays(1)
        var boundary = day.atStartOfDay(zone).toInstant().toEpochMilli()
        var guard = 0
        while (boundary <= end && guard++ < 400) {
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
