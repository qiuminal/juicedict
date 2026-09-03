package com.qiuminal.juicedict.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EditDistance 按 Unicode 码位（code point）计长与比较的回归测试：
 * 代理对（扩展区生僻字）整体算 1 个字符，避免共享高代理的无关单字被误判。
 */
class EditDistanceTest {

    private fun distance(a: String, b: String, maxScaled: Int = 20): Int {
        val n = b.codePointCount(0, b.length) + 1
        val pp = IntArray(n)
        val p = IntArray(n)
        val c = IntArray(n)
        return EditDistance.distance(a, b, maxScaled, pp, p, c)
    }

    @Test
    fun equalStringsAreZero() {
        assertEquals(0, distance("hello", "hello"))
        assertEquals(0, distance("你好", "你好"))
    }

    @Test
    fun missingLetterIsOneInsertion() {
        assertEquals(10, distance("helo", "hello"))
    }

    @Test
    fun adjacentSwapIsOneTransposition() {
        assertEquals(10, distance("recieve", "receive"))
    }

    @Test
    fun chineseMixedOrder() {
        // 制AA -> AA制：一次换位做不到，需要 2 次操作
        assertEquals(20, distance("制AA", "AA制"))
        // AA智 -> AA制：单个汉字替换
        assertEquals(10, distance("AA智", "AA制"))
    }

    @Test
    fun chineseMissingMiddleChar() {
        // 一望际 -> 一望无际：中间插入一个字
        assertEquals(10, distance("一望际", "一望无际"))
    }

    @Test
    fun supplementaryPairCountsAsOneCharacter() {
        // 𰤡 U+30921 与 𰦭 U+309AD 共享高代理 D882，按码位是 1 次整体替换；
        // 按 UTF-16 逐 char 时会被误判为只差 1 个低代理（同样 10），
        // 但长度判定必须一致按码位（1 vs 1），这正是查询层过滤的依据。
        assertEquals(10, distance("\uD882\uDD21", "\uD882\uDDAD"))
    }

    @Test
    fun lengthDifferenceEarlyExit() {
        assertTrue(distance("a", "abcdefghijklmnop", 20) > 20)
        // 代理对按 1 个字符计长：𰤡(1cp) 对 3 个 BMP 字符的超限应提前退出
        assertTrue(distance("\uD882\uDD21", "你好吗", 20) > 20)
    }
}
