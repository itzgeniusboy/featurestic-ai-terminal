package com.txdost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    @Test
    fun paragraphsSeparatedByBlankLine() {
        val blocks = MarkdownBlocks.parse("First paragraph.\n\nSecond paragraph.")
        assertEquals(2, blocks.size)
        assertEquals(MdType.PARAGRAPH, blocks[0].type)
        assertEquals(MdType.PARAGRAPH, blocks[1].type)
    }

    @Test
    fun headingsFromHashBoldAndColon() {
        val a = MarkdownBlocks.parse("## Title")
        assertEquals(MdType.HEADING, a[0].type)
        assertEquals(2, a[0].level)

        val b = MarkdownBlocks.parse("**Plan:**")
        assertEquals(MdType.HEADING, b[0].type)
        assertEquals("Plan", b[0].text)

        val c = MarkdownBlocks.parse("Plan:")
        assertEquals(MdType.HEADING, c[0].type)
    }

    @Test
    fun bulletAndNumberedLists() {
        val blocks = MarkdownBlocks.parse("- one\n- two\n1. first\n2. second")
        assertEquals(MdType.BULLET, blocks[0].type)
        assertEquals(MdType.BULLET, blocks[1].type)
        assertEquals(MdType.NUMBERED, blocks[2].type)
        assertEquals(1, blocks[2].level)
        assertEquals(MdType.NUMBERED, blocks[3].type)
        assertEquals(2, blocks[3].level)
    }

    @Test
    fun codeBlockKeepsLanguage() {
        val blocks = MarkdownBlocks.parse("```python\nprint('hi')\n```")
        assertEquals(1, blocks.size)
        assertEquals(MdType.CODE, blocks[0].type)
        assertEquals("python", blocks[0].language)
        assertEquals("print('hi')", blocks[0].text)
    }

    @Test
    fun dividerDetected() {
        val blocks = MarkdownBlocks.parse("above\n\n---\n\nbelow")
        assertTrue(blocks.any { it.type == MdType.DIVIDER })
    }

    @Test
    fun normalizeWallOfTextBecomesStructured() {
        val wall = "Plan: 1. Explain RE basics briefly. 2. List recommended tools. " +
            "- apktool: APK dekhne/modify karne ke liye " +
            "- radare2: Binary analyze karne ke liye " +
            "- Frida: hooking ke liye"
        val blocks = MarkdownBlocks.parse(MarkdownBlocks.normalize(wall))

        assertEquals(MdType.HEADING, blocks[0].type)
        assertEquals("Plan", blocks[0].text)
        assertEquals(MdType.NUMBERED, blocks[1].type)
        assertEquals(MdType.NUMBERED, blocks[2].type)
        assertEquals(MdType.BULLET, blocks[3].type)
        assertEquals(3, blocks.count { it.type == MdType.BULLET })
    }

    @Test
    fun normalizeIsIdempotentOnStructuredText() {
        val structured = "Plan:\n\n1. one\n\n2. two\n\n- a\n- b"
        val once = MarkdownBlocks.normalize(structured)
        assertEquals(once, MarkdownBlocks.normalize(once))
    }

    @Test
    fun normalTextStaysOneParagraph() {
        val blocks = MarkdownBlocks.parse("Just a simple single line reply.")
        assertEquals(1, blocks.size)
        assertEquals(MdType.PARAGRAPH, blocks[0].type)
    }

    @Test
    fun unfencedCodeBecomesCodeBlock() {
        val reply = "Ye lo function:\n\ndef add(a, b):\n    return a + b\n\nBas itna."
        val blocks = MarkdownBlocks.parse(reply)
        val code = blocks.firstOrNull { it.type == MdType.CODE }
        assertTrue("expected a CODE block", code != null)
        assertEquals("python", code!!.language)
        assertTrue(code.text.contains("def add(a, b):"))
        assertTrue(code.text.contains("return a + b"))
    }

    @Test
    fun unfencedCodeSplitByBlankLineIsFused() {
        val reply = "def add(a, b):\n    return a + b\n\ndef sub(a, b):\n    return a - b"
        val blocks = MarkdownBlocks.parse(reply)
        val codes = blocks.filter { it.type == MdType.CODE }
        assertEquals(1, codes.size)
        assertTrue(codes[0].text.contains("def add"))
        assertTrue(codes[0].text.contains("def sub"))
    }

    @Test
    fun normalMultiLineProseStaysParagraph() {
        val reply = "Ye ek normal reply hai jisme kuch lines hain.\n" +
            "Aur ye doosri line hai.\nAur ye teesri line hai."
        val blocks = MarkdownBlocks.parse(reply)
        assertEquals(1, blocks.size)
        assertEquals(MdType.PARAGRAPH, blocks[0].type)
    }
}
