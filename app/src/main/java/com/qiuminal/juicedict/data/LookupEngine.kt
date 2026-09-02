package com.qiuminal.juicedict.data

import com.qiuminal.juicedict.engine.StarDict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

data class LookupItem(
    val dictId: String,
    val dictName: String,
    val word: String,
    val offset: Long,
    val size: Int,
    val preview: String,
    /** 匹配等级：0 精确、1 前缀、2 模糊；跨词典全局排序用。 */
    val rank: Int,
)

/** 匹配等级：精确（含大小写变体与 `.syn` 别名精确命中）> 前缀 > 模糊。 */
object MatchRank {
    const val EXACT = 0
    const val PREFIX = 1
    const val FUZZY = 2

    fun of(query: String, word: String): Int = when {
        word.equals(query, ignoreCase = true) -> EXACT
        word.startsWith(query, ignoreCase = true) -> PREFIX
        else -> FUZZY
    }
}

/**
 * 跨词典排序与过滤：精确（0）> 前缀（1）> 模糊（2），同等级保持稳定序。
 * 一旦任何词典给出精确/前缀命中，就丢弃所有模糊候选——例如查“韭菜盒子”时
 * CC-CEDICT 精确命中，chibigenc 因无此词头落到模糊层返回“韭菜/八音盒子”，
 * 这些噪音不应再出现；只有当所有词典都没有精确/前缀命中时才展示模糊结果。
 */
object LookupRanking {
    fun rankAndFilter(items: List<LookupItem>): List<LookupItem> {
        val sorted = items.sortedBy { it.rank }
        return if (sorted.any { it.rank <= MatchRank.PREFIX }) {
            sorted.filter { it.rank <= MatchRank.PREFIX }
        } else {
            sorted
        }
    }
}

/**
 * Aggregates lookup results across all enabled dictionaries.
 * All heavy work happens on [Dispatchers.Default]; StarDict instances are
 * thread-safe for reads (RandomAccessFile reads are synchronized internally
 * per reader, index is immutable).
 */
class LookupEngine(private val repo: DictionaryRepository) {

    /** 智能查询：前缀优先，前缀无结果自动 fallback 到模糊查询，用户无感。 */
    suspend fun lookup(query: String): List<LookupItem> =
        withContext(Dispatchers.Default) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val dicts = repo.listEnabled()
            if (dicts.isEmpty()) return@withContext emptyList()

            dicts.map { info ->
                async {
                    val sd = repo.open(info) ?: return@async emptyList<LookupItem>()
                    sd.lookupSmart(q, 60).map { hit ->
                        LookupItem(
                            dictId = info.id,
                            dictName = info.bookName,
                            word = hit.word,
                            offset = hit.offset,
                            size = hit.size,
                            preview = runCatching { sd.article(hit).preview(200) }.getOrDefault(""),
                            rank = MatchRank.of(q, hit.word),
                        )
                    }
                }
            }.awaitAll().flatten().let { LookupRanking.rankAndFilter(it) }
        }

    fun article(dictId: String, offset: Long, size: Int) = repo.article(dictId, offset, size)
}
