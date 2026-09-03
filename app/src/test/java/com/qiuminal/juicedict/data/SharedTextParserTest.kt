package com.qiuminal.juicedict.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedTextParserTest {

    @Test
    fun nullOrBlankYieldsEmpty() {
        assertEquals("", SharedTextParser.extract(null))
        assertEquals("", SharedTextParser.extract(""))
        assertEquals("", SharedTextParser.extract("   \n \n"))
    }

    @Test
    fun takesFirstNonBlankLine() {
        assertEquals("韭菜", SharedTextParser.extract("\n\n韭菜\n盒子"))
        assertEquals("hello", SharedTextParser.extract("hello\nworld"))
    }

    @Test
    fun trimsWhitespace() {
        assertEquals("你好", SharedTextParser.extract("   你好   "))
    }

    @Test
    fun stripsWrappingQuotes() {
        assertEquals("hello", SharedTextParser.extract("\"hello\""))
        assertEquals("你好", SharedTextParser.extract("“你好”"))
        assertEquals("韭菜盒子", SharedTextParser.extract("「韭菜盒子」"))
        assertEquals("AA制", SharedTextParser.extract("『AA制』"))
        assertEquals("一望无际", SharedTextParser.extract("‘一望无际’"))
    }

    @Test
    fun doesNotStripInternalApostropheOrUnbalancedQuotes() {
        assertEquals("don't", SharedTextParser.extract("don't"))
        assertEquals("“hello", SharedTextParser.extract("“hello"))
        assertEquals("a\"b", SharedTextParser.extract("a\"b"))
    }
}
