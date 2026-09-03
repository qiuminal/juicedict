package com.qiuminal.juicedict.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Layout
import android.text.Selection
import android.text.Spannable
import android.text.style.URLSpan
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import com.qiuminal.juicedict.R
import java.net.URLDecoder
import kotlin.math.hypot

/**
 * 详情词条文本视图：使用系统原生文本选择（textIsSelectable + ActionMode 菜单），
 * 不客户端自绘选区/手柄/菜单，长按体验跟随系统控件。
 *
 * - 长按由系统弹出原生选择菜单；客户端通过 [ActionMode.Callback] 在菜单里放入
 *   「反查」（排前）与「复制」（排后）。部分 ROM 会把第三方动作折叠进“…”或
 *   前置厂商 AI 动作，属系统菜单行为，本控件不干预；
 * - 系统完成默认选词后，按 [SelectionTokens] 把选区收敛为“英文整词 / 汉字单字”，
 *   用户仍可拖动原生手柄微调扩选；
 * - 正文互见词链接（juice://lookup/…）点按触发 [onWordTap] 发起新查询；
 *   http(s) 链接交给系统浏览器。
 */
class SelectableLinkTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** 点按查词链接回调（参数为要查询的词）。 */
    var onWordTap: ((String) -> Unit)? = null

    /** 原生菜单「反查」回调（参数为当前选中的文本）。 */
    var onReverseLookup: ((String) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var longPressHandled = false
    private var activeMode: ActionMode? = null

    init {
        setTextIsSelectable(true)
        isLongClickable = true
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                activeMode = mode
                rebuildActionMenu(menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                rebuildActionMenu(menu)
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    MENU_REVERSE -> {
                        val word = selectedText()
                        if (word.isNotEmpty()) onReverseLookup?.invoke(word)
                        mode.finish()
                        return true
                    }
                    MENU_COPY -> {
                        copySelected()
                        mode.finish()
                        return true
                    }
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                if (activeMode == mode) activeMode = null
            }
        }
    }

    /** 收起选区并结束原生 ActionMode。 */
    fun dismissSelection() {
        val spannable = text as? Spannable
        if (spannable != null) {
            val start = selectionStart
            val end = selectionEnd
            if (start >= 0 && end > start) Selection.removeSelection(spannable)
        }
        activeMode?.finish()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                longPressHandled = false
            }
            MotionEvent.ACTION_UP -> {
                val moved = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > touchSlop
                val quick = event.eventTime - downTime < longPressTimeout
                if (!moved && quick && !hasSelection() && !longPressHandled &&
                    handleLinkTap(event.x, event.y)
                ) {
                    return true
                }
            }
            else -> {}
        }
        return super.onTouchEvent(event)
    }

    override fun performLongClick(): Boolean {
        longPressHandled = true
        val handled = super.performLongClick()
        if (handled) {
            val text = text?.toString() ?: ""
            val range = SelectionTokens.tokenRangeAt(text, offsetOf(downX, downY))
            if (range != null) {
                val start = range.first.coerceIn(0, text.length)
                val end = range.last.coerceIn(start, text.length)
                if (end > start && (start != selectionStart || end != selectionEnd)) {
                    val spannable = text as? Spannable
                    if (spannable != null) Selection.setSelection(spannable, start, end)
                }
            }
        }
        return handled
    }

    private fun offsetOf(x: Float, y: Float): Int {
        val layout = layout ?: return selectionStart
        val line = try {
            layout.getLineForVertical(((y - totalPaddingTop) + scrollY).toInt())
        } catch (e: Exception) {
            -1
        }
        if (line < 0 || line >= layout.lineCount) return selectionStart
        val len = text?.length ?: 0
        return (layout.getOffsetForHorizontal(line, (x - totalPaddingLeft) + scrollX)).coerceIn(0, len)
    }

    private fun handleLinkTap(x: Float, y: Float): Boolean {
        val spannable = text as? Spannable ?: return false
        val layout = layout ?: return false
        val textLen = text?.length ?: 0
        return try {
            val line = layout.getLineForVertical(((y - totalPaddingTop) + scrollY).toInt())
            if (line < 0 || line >= layout.lineCount) return false
            val offset = layout.getOffsetForHorizontal(line, (x - totalPaddingLeft) + scrollX)
            if (offset < 0 || offset >= textLen) return false
            val spans = spannable.getSpans(
                (offset - 1).coerceAtLeast(0),
                (offset + 1).coerceAtMost(textLen),
                URLSpan::class.java,
            )
            val span = spans.firstOrNull { spannable.getSpanStart(it) <= offset && offset < spannable.getSpanEnd(it) }
                ?: spans.firstOrNull()
            val url = span?.url ?: return false
            if (url.startsWith(LOOKUP_SCHEME)) {
                val word = try {
                    URLDecoder.decode(url.substring(LOOKUP_SCHEME.length), "UTF-8")
                } catch (e: Exception) {
                    null
                }
                if (word != null && word.isNotBlank()) {
                    onWordTap?.invoke(word.trim())
                    return true
                }
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                }
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun selectedText(): String {
        val start = selectionStart
        val end = selectionEnd
        if (start < 0 || end <= start) return ""
        return (text?.subSequence(start, end)?.toString() ?: "").trim()
    }

    private fun copySelected() {
        val word = selectedText()
        if (word.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("juicedict", word))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /** 原生选择菜单：清掉系统默认项后放入「反查 / 复制」，反查在前。 */
    private fun rebuildActionMenu(menu: Menu) {
        menu.clear()
        if (hasSelection()) {
            menu.add(0, MENU_REVERSE, 0, R.string.reverse_lookup)
            menu.add(0, MENU_COPY, 1, R.string.copy)
        }
    }

    private companion object {
        const val LOOKUP_SCHEME = "juice://lookup/"
        const val MENU_COPY = 1
        const val MENU_REVERSE = 2
    }
}
