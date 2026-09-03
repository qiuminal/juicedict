package com.qiuminal.juicedict.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 代理对（扩展区生僻字）模糊查询回归：输入 𰤡（U+30921）不应把毫不相关的
 * 单字 𰦭（U+309AD，共享高代理 U+D882）当作模糊命中；单字查询查无结果时应
 * 返回空列表交由 UI 提示“未找到”。英文错字/缺字/乱序的中英统一逻辑不受影响。
 */
class StarDictSupplementaryTest {

    private fun ifo(wordCount: Long) = Ifo(
        version = "3.0.0",
        bookName = "test",
        wordCount = wordCount,
        idxFileSize = 0,
        sameTypeSequence = "h",
        synWordCount = 0,
        idxOffsetBits = 32,
        dictType = null,
        author = null,
        email = null,
        website = null,
        date = null,
        description = null,
    )

    private fun writeIntBE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xff)
        out.write((v ushr 16) and 0xff)
        out.write((v ushr 8) and 0xff)
        out.write(v and 0xff)
    }

    /** 构造 .idx 字节：每词条 word\0 + u32 BE 偏移 + u32 BE 长度。 */
    private fun idxBytes(vararg words: String): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        for (w in words) {
            val bytes = w.toByteArray(StandardCharsets.UTF_8)
            out.write(bytes)
            out.write(0)
            writeIntBE(out, offset)
            writeIntBE(out, bytes.size)
            offset += bytes.size + 1
        }
        return out.toByteArray()
    }

    @Test
    fun unrelatedSupplementarySingleCharsDoNotFuzzyMatch() {
        val tmp = File.createTempFile("juicedict-supp", ".dict")
        tmp.deleteOnExit()
        tmp.writeText("\uD882\uDDAD：某字释义。\nhello：你好。\n韭菜：一种蔬菜。")
        try {
            val sd = StarDict(
                "test",
                ifo(3),
                StarDictIndex.load(ifo(3), idxBytes("\uD882\uDDAD", "hello", "韭菜")),
                PlainDictReader(tmp),
            )
            // 输入 𰤡（U+30921），词库只有 𰦭（U+309AD）：两字不同、单字查询
            // 既无前缀也无同义别名，模糊层也不应把共享高代理的无关字当命中。
            assertTrue(sd.lookupSmart("\uD882\uDD21", 60).isEmpty())
            assertTrue(sd.lookupFuzzy("\uD882\uDD21", 20).isEmpty())
            // 前缀层正常：词库确有以 𰦭 开头的词条时仍能查到
            assertEquals("\uD882\uDDAD", sd.lookupSmart("\uD882\uDDAD", 60).first().word)
            // 常规 BMP 模糊能力不回退：错字/缺字仍能命中
            assertTrue(sd.lookupSmart("helo", 60).any { it.word == "hello" })
            assertTrue(sd.lookupSmart("九菜", 60).any { it.word == "韭菜" })
            sd.close()
        } finally {
            tmp.delete()
        }
    }
}
