package timbo.homebrew.magpiescreen.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import timbo.homebrew.magpiescreen.R
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * GitHub-style year grid: one cell per day, 53 weeks across and 7 rows down (Mon..Sun).
 * Days outside the gateway's history are left empty; the rest are shaded by activity.
 */
class HeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class Day(val date: LocalDate, val calls: Int, val errors: Int, val tokens: Long, val cost: Double)

    private var days: Map<LocalDate, Day> = emptyMap()
    private var since: LocalDate? = null
    private var today: LocalDate = LocalDate.now()

    /** The grid is anchored on the gateway's own day, not the phone's clock. */
    private var onDay: ((Day, Float, Float) -> Unit)? = null
    private var columns = 53
    private var fillHeight = true

    private var firstMonday: LocalDate = today
    private var cellW: Float = 0f
    private var cellH: Float = 0f
    private var gap: Float = 0f
    private var originX: Float = 0f
    private var originY: Float = 0f

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.card) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted)
        textSize = 9f * resources.displayMetrics.density
    }
    private val rect = RectF()

    private val levelColors = intArrayOf(
        0,
        context.getColor(R.color.heat1),
        context.getColor(R.color.heat2),
        context.getColor(R.color.heat3),
        context.getColor(R.color.heat4),
    )
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setData(
        days: Map<LocalDate, Day>,
        since: LocalDate?,
        today: LocalDate,
        columns: Int = 53,
        fillHeight: Boolean = true,
        onDay: ((Day, Float, Float) -> Unit)? = null,
    ) {
        this.days = days
        this.since = since
        this.today = today
        this.columns = columns
        this.fillHeight = fillHeight
        this.onDay = onDay
        invalidate()
    }

    /** Highest call count, used to bucket the four shades. */
    private fun maxCalls(): Int = days.values.maxOfOrNull { it.calls } ?: 0

    private fun levelOf(value: Int, max: Int): Int {
        if (value <= 0 || max <= 0) return 0
        val ratio = value.toFloat() / max
        return when {
            ratio <= 0.25f -> 1
            ratio <= 0.50f -> 2
            ratio <= 0.75f -> 3
            else -> 4
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val rows = 7
        val density = resources.displayMetrics.density
        gap = 2f * density
        val labelsWidth = 22f * density
        val monthsHeight = 13f * density

        firstMonday = today.with(DayOfWeek.MONDAY).minusWeeks((columns - 1).toLong())
        val widthCell = (w - labelsWidth - gap * (columns - 1)) / columns
        val heightCell = (h - monthsHeight - gap * (rows - 1)) / rows
        if (widthCell <= 0f || heightCell <= 0f) return
        cellW = if (fillHeight) widthCell else minOf(widthCell, heightCell)
        cellH = if (fillHeight) heightCell else cellW
        originX = labelsWidth
        originY = monthsHeight + if (fillHeight) 0f else ((h - monthsHeight) - (rows * cellH + (rows - 1) * gap)) / 2f

        val max = maxCalls()
        for (col in 0 until columns) {
            for (row in 0 until rows) {
                val date = firstMonday.plusWeeks(col.toLong()).plusDays(row.toLong())
                if (date.isAfter(today)) continue
                val x = originX + col * (cellW + gap)
                val y = originY + row * (cellH + gap)
                rect.set(x, y, x + cellW, y + cellH)
                val known = since == null || !date.isBefore(since)
                val day = days[date]
                val paint = when {
                    !known -> emptyPaint.apply { alpha = 60 }
                    day == null || day.calls <= 0 -> emptyPaint
                    else -> levelPaint.apply { color = levelColors[levelOf(day.calls, max)] }
                }
                canvas.drawRoundRect(rect, 2f * density, 2f * density, paint)
            }
        }

        // weekday labels on the left
        val labelRows = listOf(0 to "一", 2 to "三", 4 to "五", 6 to "日")
        labelRows.forEach { (row, label) ->
            val y = originY + row * (cellH + gap) + cellH / 2f + textPaint.textSize / 3f
            canvas.drawText(label, 2f, y, textPaint)
        }

        // month labels, skipped when the previous one is still close
        var lastMonth = -1
        var lastYear = -1
        var lastLabelX = -100f
        for (col in 0 until columns) {
            val monday = firstMonday.plusWeeks(col.toLong())
            if (monday.monthValue == lastMonth) continue
            lastMonth = monday.monthValue
            val x = originX + col * (cellW + gap)
            if (x > w - 24f * density) break
            if (x - lastLabelX < 30f * density) continue
            val label = if (monday.year != lastYear) {
                "%d年%d月".format(monday.year, monday.monthValue)
            } else {
                "%d月".format(monday.monthValue)
            }
            lastYear = monday.year
            canvas.drawText(label, x, monthsHeight - 4f, textPaint)
            lastLabelX = x
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onDay == null) return false
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP) return false
        val col = ((event.x - originX) / (cellW + gap)).toInt()
        val row = ((event.y - originY) / (cellH + gap)).toInt()
        if (col !in 0..52 || row !in 0..6) return false
        val date = firstMonday.plusWeeks(col.toLong()).plusDays(row.toLong())
        if (date.isAfter(today)) return false
        val x = originX + col * (cellW + gap)
        val y = originY + row * (cellH + gap)
        days[date]?.let { onDay?.invoke(it, x + cellW / 2f, y) }
        return true
    }
}
