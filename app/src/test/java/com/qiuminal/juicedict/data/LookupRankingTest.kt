package com.qiuminal.juicedict.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 跨词典候选排序与模糊过滤规则测试。 */
class LookupRankingTest {

    private fun item(word: String, dict: String = "d", rank: Int) =
        LookupItem(dict, dict, word, 0L, 0, "", rank)

    @Test
    fun exactMatchesRankBeforePrefix() {
        // 查“三军”：CC 三军(精确) + 三军用命(前缀)，chibigenc 三军(精确)
        val out = LookupRanking.rankAndFilter(
            listOf(
                item("三军用命", "CC", MatchRank.PREFIX),
                item("三军", "chibigenc", MatchRank.EXACT),
                item("三军", "CC", MatchRank.EXACT),
            ),
        )
        assertEquals(listOf("三军", "三军", "三军用命"), out.map { it.word })
        // 同等级稳定序：保持输入顺序（chibigenc 的精确命中先于 CC 的）
        assertEquals(listOf("chibigenc", "CC", "CC"), out.map { it.dictName })
    }

    @Test
    fun fuzzySuppressedWhenExactOrPrefixExists() {
        // 查“韭菜盒子”：CC 精确命中，chibigenc 模糊返回“韭菜/八音盒子”，应被丢弃
        val out = LookupRanking.rankAndFilter(
            listOf(
                item("韭菜", "chibigenc", MatchRank.FUZZY),
                item("韭菜盒子", "CC", MatchRank.EXACT),
                item("八音盒子", "chibigenc", MatchRank.FUZZY),
            ),
        )
        assertEquals(listOf("韭菜盒子"), out.map { it.word })
    }

    @Test
    fun fuzzySuppressedWhenOnlyPrefixExists() {
        val out = LookupRanking.rankAndFilter(
            listOf(
                item("hell", "d1", MatchRank.PREFIX),
                item("help", "d1", MatchRank.PREFIX),
                item("hello", "d2", MatchRank.FUZZY),
            ),
        )
        assertEquals(listOf("hell", "help"), out.map { it.word })
    }

    @Test
    fun fuzzyKeptWhenNothingBetter() {
        // 查“一望际”：无词典有前缀命中，保留所有模糊候选
        val out = LookupRanking.rankAndFilter(
            listOf(
                item("一望无际", "CC", MatchRank.FUZZY),
                item("一望无涯", "chibigenc", MatchRank.FUZZY),
            ),
        )
        assertEquals(listOf("一望无际", "一望无涯"), out.map { it.word })
    }
}
