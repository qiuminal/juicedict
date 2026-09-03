package com.qiuminal.juicedict.engine

/**
 * 统一的 Damerau-Levenshtein 编辑距离（含相邻换位），中英文同一套规则：
 * 插入、删除、替换、相邻换位都算 1 次操作（×10 缩放为 10）。
 *
 * 距离按 Unicode 码位（code point）计算与计长：CJK 扩展区的代理对（如
 * 𰤡 U+30921）整体视为 1 个字符，避免“两个生僻字共享同一个高代理、只差低
 * 代理”被按 UTF-16 逐 char 比较时误判成只差 1 次替换，从而把毫不相关的单字
 * 错当成模糊命中。全 BMP 输入时 1 个 char 即 1 个码位，走快速路径，速度不变。
 *
 * 中文不做拼音/模糊音特判：`制` 与 `智` 与任何其它错字一样按替换计 1 次；
 * 成语少打一个字（一望际 -> 一望无际）按插入计 1 次；汉字乱序（一望际无 ->
 * 一望无际）按相邻换位计 1 次——与英文 helo -> hello、recieve -> receive
 * 完全同一套逻辑。这样既保证“乱序/缺字/错字”同权，也避免逐字符查拼音表
 * 拖慢全索引扫描。
 *
 * 工作区数组由调用方复用（每个长度至少为 b 的码位数 + 1），整次扫描零分配。
 */
object EditDistance {

    /**
     * 返回 [a] 到 [b] 的编辑距离（按码位计，代理对整体算 1 个字符）；
     * 超过 [maxScaled] 时返回 [maxScaled] + 1。
     * [prevPrev]、[prev]、[cur] 为长度至少 b 的码位数 + 1 的复用工作区。
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
        val m = a.codePointCount(0, a.length)
        val n = b.codePointCount(0, b.length)
        if (kotlin.math.abs(m - n) * 10 > maxScaled) return maxScaled + 1
        if (n > prev.size - 1) return maxScaled + 1
        // 全 BMP（char 数 == 码位数）时快速路径与旧实现一致，零额外开销。
        return if (m == a.length && n == b.length) {
            distanceBmp(a, b, m, n, maxScaled, prevPrev, prev, cur)
        } else {
            distanceCodePoints(a, b, m, n, maxScaled, prevPrev, prev, cur)
        }
    }

    private fun distanceBmp(
        a: String,
        b: String,
        m: Int,
        n: Int,
        maxScaled: Int,
        prevPrev: IntArray,
        prev: IntArray,
        cur: IntArray,
    ): Int {
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

    /** 慢速路径：含代理对时按码位逐行逐列比较；此类输入罕见，开销可忽略。 */
    private fun distanceCodePoints(
        a: String,
        b: String,
        m: Int,
        n: Int,
        maxScaled: Int,
        prevPrev: IntArray,
        prev: IntArray,
        cur: IntArray,
    ): Int {
        var pp = prevPrev
        var p = prev
        var c = cur
        for (j in 0..n) {
            p[j] = j * 10
            pp[j] = j * 10
        }
        var aOff = 0
        var aPrev = -1 // a 的上一个码位（0-based i-2），供换位判定
        for (i in 1..m) {
            val ca = a.codePointAt(aOff)
            aOff += Character.charCount(ca)
            c[0] = i * 10
            var rowMin = c[0]
            var bOff = 0
            var bPrev = -1 // b 的上一列码位（0-based j-2），供换位判定
            for (j in 1..n) {
                val cb = b.codePointAt(bOff)
                bOff += Character.charCount(cb)
                val cost = if (ca == cb) 0 else 10
                var v = minOf(p[j] + 10, c[j - 1] + 10, p[j - 1] + cost)
                if (i > 1 && j > 1 && ca == bPrev && aPrev == cb) {
                    v = minOf(v, pp[j - 2] + 10)
                }
                c[j] = v
                if (v < rowMin) rowMin = v
                bPrev = cb
            }
            if (rowMin > maxScaled) return maxScaled + 1
            val t = pp
            pp = p
            p = c
            c = t
            aPrev = ca
        }
        return p[n]
    }
}
