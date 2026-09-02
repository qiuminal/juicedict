package com.qiuminal.juicedict.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * 中文两端对齐排版视图。
 *
 * - 按 \n 分段，段内除末行外全部两端对齐（末行保持左对齐，避免短行被拉宽）；
 * - 对齐间距只加在 汉字/全角标点/空格 的边界上，拉丁单词内部不拉伸；
 * - 内置避头尾规则：行首不允许出现句末标点（，。、；：？！）》… 等），
 *   行尾不允许出现句首标点（（《〈「『【… 等），避免换行产生突凹感；
 */
class JustifyTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private var content: CharSequence = ""
    private var textColor = Color.parseColor("#333333")
    private var textSizePx = sp2px(15f)
    private var lineSpacingExtraPx = dp2px(6f)

    private val lines = mutableListOf<Line>()
    private var totalHeight = 0f

    fun setText(text: CharSequence?) {
        content = text ?: ""
        relayout()
    }

    fun setTextSizeSp(sp: Float) {
        textSizePx = sp2px(sp)
        relayout()
    }

    fun setLineSpacingExtraDp(dp: Float) {
        lineSpacingExtraPx = dp2px(dp)
        relayout()
    }

    fun setTextColor(color: Int) {
        textColor = color
        invalidate()
    }

    /** 字体加载完成后重建排版（内置字体选择变化会影响字符宽度与行高）。 */
    fun rebuild() {
        relayout()
    }

    private fun relayout() {
        if (width > 0) {
            buildLines((width - paddingLeft - paddingRight).coerceAtLeast(0))
            invalidate()
        } else {
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val availW = (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        buildLines(availW)
        val height = (totalHeight + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                suggestedMinimumWidth
            } else {
                widthSize
            },
            resolveSize(height, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) {
            buildLines((w - paddingLeft - paddingRight).coerceAtLeast(0))
            invalidate()
        }
    }

    // ---------------- 排版 ----------------

    private fun buildLines(availW: Int) {
        lines.clear()
        totalHeight = 0f
        if (availW <= 0 || content.isEmpty()) {
            return
        }
        val n = content.length
        var paraStart = 0
        while (paraStart < n) {
            var paraEnd = paraStart
            while (paraEnd < n && content[paraEnd] != '\n') {
                paraEnd++
            }
            if (paraEnd > paraStart) {
                layoutParagraph(paraStart, paraEnd, availW.toFloat())
            } else {
                addBlankLine()
            }
            paraStart = paraEnd + 1
        }
        if (lines.isNotEmpty()) {
            totalHeight -= lineSpacingExtraPx
        }
    }

    /** 空段（\n\n）渲染为一行空行，用于版本日志之间留白。 */
    private fun addBlankLine() {
        paint.textSize = textSizePx
        paint.typeface = null
        paint.isFakeBoldText = false
        val fm = paint.fontMetrics
        val ascent = -fm.ascent
        val descent = fm.descent
        lines.add(Line(emptyList(), 0f, 0f, ascent, descent))
        totalHeight += ascent + descent + lineSpacingExtraPx
    }

    private fun layoutParagraph(start: Int, end: Int, availW: Float) {
        var runs = mutableListOf<Run>()
        var runWidth = 0f
        var i = start
        while (i < end) {
            val run = runFor(content[i], i)
            if (runs.isEmpty() || runWidth + run.width <= availW) {
                runs.add(run)
                runWidth += run.width
                i++
                continue
            }
            if (isForbiddenLineStart(content[i])) {
                // 句末标点不能起行：回退本行末尾字符，让标点随其后顺延到下一行（保持原文顺序）
                val carried = mutableListOf<Run>()
                while (runs.isNotEmpty()) {
                    val removed = runs.removeAt(runs.size - 1)
                    runWidth -= removed.width
                    carried.add(0, removed)
                    if (!isForbiddenLineStart(carried.first().c)) {
                        break
                    }
                }
                if (runs.isNotEmpty()) {
                    finishLine(runs, runWidth, availW, isLast = false)
                }
                runs = carried
                runWidth = carried.fold(0f) { acc, r -> acc + r.width }
                if (runs.isEmpty() || runWidth + run.width <= availW) {
                    runs.add(run)
                    runWidth += run.width
                    i++
                }
                // 若回退后仍放不下，下一轮继续回退（每轮至少回退一个字符，最终标点单独成行）
            } else {
                // 行尾避头尾：行尾不能是句首标点，将其顺延到下一行行首
                val carried = mutableListOf<Run>()
                while (runs.isNotEmpty() && isForbiddenLineEnd(runs.last().c)) {
                    val removed = runs.removeAt(runs.size - 1)
                    runWidth -= removed.width
                    carried.add(removed)
                }
                if (runs.isNotEmpty()) {
                    finishLine(runs, runWidth, availW, isLast = false)
                }
                runs = carried.asReversed().toMutableList()
                runWidth = runs.fold(0f) { acc, r -> acc + r.width }
            }
        }
        if (runs.isNotEmpty()) {
            finishLine(runs, runWidth, availW, isLast = true)
        }
    }

    private fun finishLine(runs: List<Run>, runWidth: Float, availW: Float, isLast: Boolean) {
        if (runs.isEmpty()) {
            return
        }
        var maxAscent = 0f
        var maxDescent = 0f
        for (r in runs) {
            paint.textSize = textSizePx
            paint.typeface = r.font
            paint.isFakeBoldText = r.bold
            val fm = paint.fontMetrics
            if (-fm.ascent > maxAscent) {
                maxAscent = -fm.ascent
            }
            if (fm.descent > maxDescent) {
                maxDescent = fm.descent
            }
        }
        var gap = 0f
        if (!isLast && runs.size > 1) {
            val extra = availW - runWidth
            if (extra > 0f) {
                val justifiable = countJustifiableGaps(runs)
                if (justifiable > 0) {
                    gap = extra / justifiable
                }
            }
        }
        lines.add(Line(runs, runWidth, gap, maxAscent, maxDescent))
        totalHeight += maxAscent + maxDescent + lineSpacingExtraPx
    }

    private fun countJustifiableGaps(runs: List<Run>): Int {
        var count = 0
        for (i in 0 until runs.size - 1) {
            if (isJustifiableGap(runs[i], runs[i + 1])) {
                count++
            }
        }
        return count
    }

    private fun isJustifiableGap(left: Run, right: Run): Boolean =
        isCjkOrSpace(left.c) || isCjkOrSpace(right.c)

    private fun runFor(c: Char, index: Int): Run {
        val spanned = content as? Spanned
        val bold = spanned?.getSpans(index, index + 1, StyleSpan::class.java)?.isNotEmpty() ?: false
        val font: Typeface? = null
        paint.textSize = textSizePx
        paint.typeface = font
        paint.isFakeBoldText = bold
        return Run(c, paint.measureText(c.toString()), font, bold)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty()) {
            return
        }
        var y = paddingTop + lines[0].ascent
        for (line in lines) {
            var x = paddingLeft.toFloat()
            for (i in line.runs.indices) {
                val r = line.runs[i]
                paint.textSize = textSizePx
                paint.typeface = r.font
                paint.isFakeBoldText = r.bold
                paint.color = textColor
                canvas.drawText(r.c.toString(), x, y, paint)
                x += r.width
                if (i < line.runs.size - 1 && line.gap > 0f && isJustifiableGap(r, line.runs[i + 1])) {
                    x += line.gap
                }
            }
            y += line.ascent + line.descent + lineSpacingExtraPx
        }
    }

    private class Run(val c: Char, val width: Float, val font: Typeface?, val bold: Boolean)

    private class Line(
        val runs: List<Run>,
        val width: Float,
        val gap: Float,
        val ascent: Float,
        val descent: Float
    )

    private fun isForbiddenLineStart(c: Char): Boolean = c in FORBIDDEN_LINE_START

    private fun isForbiddenLineEnd(c: Char): Boolean = c in FORBIDDEN_LINE_END

    private fun isCjkOrSpace(c: Char): Boolean {
        if (c == ' ' || c == '\u3000') {
            return true
        }
        val v = c.code
        return v in 0x2E80..0x2EFF ||
                v in 0x3000..0x303F ||
                v in 0x3400..0x4DBF ||
                v in 0x4E00..0x9FFF ||
                v in 0xF900..0xFAFF ||
                v in 0xFF00..0xFFEF ||
                v in 0x20000..0x2FA1F
    }

    private fun sp2px(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    private fun dp2px(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private companion object {
        // 行首禁则：句末/停顿标点等不允许出现在行首
        const val FORBIDDEN_LINE_START = "，。、；：？！）》】」』”’…—·～｝〕〗〙〟〞｡､｣"
        // 行末禁则：句首标点等不允许出现在行尾
        const val FORBIDDEN_LINE_END = "（《〈「『【〔〖｛“‘"
    }
}