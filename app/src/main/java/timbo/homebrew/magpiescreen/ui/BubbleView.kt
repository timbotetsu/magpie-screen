package timbo.homebrew.magpiescreen.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import timbo.homebrew.magpiescreen.R

/**
 * The small card that pops next to a chip or heat-map cell: a title, one totals line and a
 * three-column list (name | 请求 | Token). Rows can be filled later, e.g. after a fetch.
 */
object BubbleView {

    data class Row(val name: String, val calls: String, val tokens: String)

    class Handle internal constructor(
        private val popup: PopupWindow,
        private val card: LinearLayout,
        private val rowsBox: LinearLayout,
        private val x: Int,
        private val y: Int,
    ) {
        val isShowing: Boolean get() = popup.isShowing

        fun setRows(rows: List<Row>, footnote: String?) {
            if (!popup.isShowing) return
            rowsBox.removeAllViews()
            rowsBox.addView(divider(rowsBox.context))
            rowsBox.addView(columns(rowsBox.context, "模型", "请求", "Token", R.color.muted, 8.5f))
            if (rows.isEmpty()) {
                rowsBox.addView(
                    TextView(rowsBox.context).apply {
                        text = "无数据"
                        setTextColor(rowsBox.context.getColor(R.color.muted))
                        textSize = 9f
                    },
                )
            } else {
                rows.forEach { row ->
                    rowsBox.addView(columns(rowsBox.context, row.name, row.calls, row.tokens, R.color.fg, 9f))
                }
                footnote?.let {
                    rowsBox.addView(
                        TextView(rowsBox.context).apply {
                            text = it
                            setTextColor(rowsBox.context.getColor(R.color.muted))
                            textSize = 9f
                        },
                    )
                }
            }
            val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            card.measure(spec, spec)
            popup.update(x, y, card.measuredWidth, card.measuredHeight)
        }

        fun dismiss() {
            if (popup.isShowing) popup.dismiss()
        }
    }

    /**
     * @param anchorPointX screen x the bubble should point at
     * @param anchorPointY screen y of the anchor edge (cell top or chip bottom)
     * @param below true -> under the anchor point, false -> above it
     */
    fun show(
        parent: View,
        anchorPointX: Int,
        anchorPointY: Int,
        below: Boolean,
        title: String,
        totals: String,
        visibleMs: Long = 5000,
    ): Handle {
        val ctx = parent.context
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(ctx, 9), dp(ctx, 6), dp(ctx, 9), dp(ctx, 6))
        }
        card.addView(
            TextView(ctx).apply {
                text = title
                setTextColor(ctx.getColor(R.color.muted))
                textSize = 9f
            },
        )
        card.addView(
            TextView(ctx).apply {
                text = totals
                setTextColor(ctx.getColor(R.color.fg))
                textSize = 10f
            },
        )
        val rowsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 260), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        card.addView(rowsBox)

        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        card.measure(spec, spec)
        val metrics = ctx.resources.displayMetrics
        val width = card.measuredWidth
        val height = card.measuredHeight
        val x = (anchorPointX - width / 2).coerceIn(dp(ctx, 4), metrics.widthPixels - width - dp(ctx, 4))
        var y = if (below) anchorPointY + dp(ctx, 4) else anchorPointY - height - dp(ctx, 4)
        if (y < dp(ctx, 4)) y = anchorPointY + dp(ctx, 4)
        if (y + height > metrics.heightPixels - dp(ctx, 4)) y = metrics.heightPixels - height - dp(ctx, 4)

        val popup = PopupWindow(card, width, height, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(ctx, 6).toFloat()
        }
        popup.showAtLocation(parent, Gravity.TOP or Gravity.START, x, y)
        val handle = Handle(popup, card, rowsBox, x, y)
        card.postDelayed({ handle.dismiss() }, visibleMs)
        return handle
    }

    private fun columns(
        ctx: Context,
        name: String,
        calls: String,
        tokens: String,
        colorRes: Int,
        sizeSp: Float,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 2) }
        addView(
            TextView(ctx).apply {
                text = name
                setTextColor(ctx.getColor(colorRes))
                textSize = sizeSp
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        addView(
            TextView(ctx).apply {
                text = calls
                setTextColor(ctx.getColor(colorRes))
                textSize = sizeSp
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), LinearLayout.LayoutParams.WRAP_CONTENT)
            },
        )
        addView(
            TextView(ctx).apply {
                text = tokens
                setTextColor(ctx.getColor(colorRes))
                textSize = sizeSp
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 56), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(ctx, 6) }
            },
        )
    }

    private fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(ctx.getColor(R.color.line))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(ctx, 1),
        ).apply {
            topMargin = dp(ctx, 5)
            bottomMargin = dp(ctx, 3)
        }
    }

    private fun dp(ctx: Context, value: Int): Int = (value * ctx.resources.displayMetrics.density).toInt()
}
