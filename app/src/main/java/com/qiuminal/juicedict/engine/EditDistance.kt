package com.qiuminal.juicedict.engine

/**
 * 统一的 Damerau-Levenshtein 编辑距离（含相邻换位），中英文同一套规则：
 * 插入、删除、替换、相邻换位都算 1 次操作（×10 缩放为 10）。
 *
 * 中文不做拼音/模糊音特判：`制` 与 `智` 与任何其它错字一样按替换计 1 次；
 * 成语少打一个字（一望际 -> 一望无际）按插入计 1 次；汉字乱序（一望际无 ->
 * 一望无际）按相邻换位计 1 次——与英文 helo -> hello、recieve -> receive
 * 完全同一套逻辑。这样既保证“乱序/缺字/错字”同权，也避免逐字符查拼音表
 * 拖慢全索引扫描。
 *
 * 工作区数组由调用方复用，整次扫描零分配。
 */
object EditDistance {

    /**
     * 返回 [a] 到 [b] 的编辑距离；超过 [maxScaled] 时返回 [maxScaled] + 1。
     * [prevPrev]、[prev]、[cur] 为长度至少 [b.length] + 1 的复用工作区。
     * 每行最小值随行数单调不减，因此可在超过上限时提前退出。
     */
    fun distance(
        a: String,
        b: String,
        maxScaled: Int,
        prevPrev: IntArray,
        prev: IntArray,
        cur: IntArray,
    ): Int {
        if (kotlin.math.abs(a.length - b.length) * 10 > maxScaled) return maxScaled + 1
        val m = a.length
        val n = b.length
        var pp = prevPrev
        var p = prev
        var c = cur
        for (j in 0..n) {
            p[j] = j * 10
            pp[j] = j * 10
        }
        for (i in 1..m) {
            c[0] = i * 10
            var rowMin = c[0]
            val ca = a[i - 1]
            val before = if (i > 1) a[i - 2] else '\u0000'
            for (j in 1..n) {
                val cost = if (ca == b[j - 1]) 0 else 10
                var v = minOf(p[j] + 10, c[j - 1] + 10, p[j - 1] + cost)
                if (i > 1 && j > 1 && ca == b[j - 2] && before == b[j - 1]) {
                    v = minOf(v, pp[j - 2] + 10)
                }
                c[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > maxScaled) return maxScaled + 1
            val t = pp
            pp = p
            p = c
            c = t
        }
        return p[n]
    }
}
