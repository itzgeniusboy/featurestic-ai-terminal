package com.txdost

/**
 * Lightweight, dependency-free markdown block parser + a "wall of text"
 * safety net for weak models. Used by the chat UI to render each logical
 * block as its own composable (paragraph / heading / list / code / quote /
 * divider) instead of one dense Text.
 *
 * Nothing here touches Android APIs, so it is unit-testable on the JVM.
 */

enum class MdType { PARAGRAPH, HEADING, BULLET, NUMBERED, QUOTE, DIVIDER, CODE, COMMAND }

data class MdBlock(
    val type: MdType,
    val text: String,
    /** heading level (1-6) for HEADING, list number for NUMBERED */
    val level: Int = 0,
    val language: String = ""
)

object MarkdownBlocks {

    private val headingRe  = Regex("^#{1,6}\\s+(.*)$")
    private val boldOnlyRe = Regex("^\\*\\*(.+?)\\*\\*:?\\s*$")
    private val bulletRe   = Regex("^\\s*[-*+]\\s+(.*)$")
    private val numberedRe = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
    private val quoteRe    = Regex("^\\s*>\\s?(.*)$")
    private val dividerRe  = Regex("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")
    private val termuxRe   = Regex("""\[TERMUX:(.*?)\]""", RegexOption.DOT_MATCHES_ALL)

    /** Parse raw (already tag-cleaned) text into display blocks. */
    fun parse(raw: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').lines()
        var i = 0
        val para = StringBuilder()

        fun flushPara() {
            val t = para.toString().trim('\n').trim()
            para.setLength(0)
            if (t.isEmpty()) return
            val single = !t.contains('\n')
            blocks.add(
                when {
                    single && t.length in 1..48 && t.endsWith(':') && !t.startsWith("-") ->
                        MdBlock(MdType.HEADING, TextUtils.stripEmoji(t.trimEnd(':').trim()), level = 2)
                    else ->
                        MdBlock(MdType.PARAGRAPH, TextUtils.stripEmoji(t))
                }
            )
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flushPara()
                    val lang = trimmed.removePrefix("```").trim()
                    val sb = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        sb.append(lines[i]).append('\n'); i++
                    }
                    blocks.add(MdBlock(MdType.CODE, sb.toString().trimEnd('\n'), language = lang))
                }
                line.contains("[TERMUX:") -> {
                    flushPara()
                    val cmd = termuxRe.replace(line) { it.groupValues[1] }.trim()
                    if (cmd.isNotEmpty()) blocks.add(MdBlock(MdType.COMMAND, TextUtils.stripEmoji(cmd)))
                }
                trimmed.startsWith("$ ") -> {
                    flushPara()
                    blocks.add(MdBlock(MdType.COMMAND, TextUtils.stripEmoji(trimmed.removePrefix("$ ").trim())))
                }
                dividerRe.matches(trimmed) -> { flushPara(); blocks.add(MdBlock(MdType.DIVIDER, "")) }
                headingRe.matches(trimmed) -> {
                    flushPara()
                    val m = headingRe.find(trimmed)!!
                    blocks.add(MdBlock(MdType.HEADING, TextUtils.stripEmoji(m.groupValues[1].trim()),
                        level = trimmed.takeWhile { it == '#' }.length))
                }
                boldOnlyRe.matches(trimmed) -> {
                    flushPara()
                    val t = boldOnlyRe.find(trimmed)!!.groupValues[1].trimEnd(':').trim()
                    blocks.add(MdBlock(MdType.HEADING, TextUtils.stripEmoji(t), level = 2))
                }
                numberedRe.matches(line) -> {
                    flushPara()
                    val m = numberedRe.find(line)!!
                    blocks.add(MdBlock(MdType.NUMBERED, TextUtils.stripEmoji(m.groupValues[2].trim()),
                        level = m.groupValues[1].toIntOrNull() ?: 1))
                }
                bulletRe.matches(line) -> {
                    flushPara()
                    blocks.add(MdBlock(MdType.BULLET, TextUtils.stripEmoji(bulletRe.find(line)!!.groupValues[1].trim())))
                }
                quoteRe.matches(line) -> {
                    flushPara()
                    blocks.add(MdBlock(MdType.QUOTE, TextUtils.stripEmoji(quoteRe.find(line)!!.groupValues[1].trim())))
                }
                trimmed.isEmpty() -> flushPara()
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line.trimEnd())
                }
            }
            i++
        }
        flushPara()
        return blocks
    }

    /**
     * Safety net for weak models that still send one dense blob.
     * Idempotent on already-structured text; call on DISPLAY only.
     */
    fun normalize(input: String): String {
        if (input.isBlank()) return input
        var text = input.replace("\r\n", "\n").replace('\r', '\n').trim()

        if (!text.contains("\n\n")) {
            // Inline numbered list: "... 1. x 2. y" -> own lines
            val numbered = Regex("(?<=[\\s:;.!?])\\s*(\\d{1,2})[.)]\\s+")
            if (numbered.findAll(text).count() >= 2) {
                text = numbered.replace(text, "\n\n$1. ")
            }
            // Inline bullets: "... - a - b" -> own lines
            val bullets = Regex("\\s+[-•]\\s+")
            if (bullets.findAll(text).count() >= 2) {
                text = bullets.replace(text, "\n- ")
            }
            // Inline "Step 1 / Step 2"
            if (Regex("\\s(?=Step\\s+\\d)").findAll(text).count() >= 1) {
                text = Regex("\\s+(?=Step\\s+\\d)").replace(text, "\n\n")
            }
            text = text.replace(Regex("\n{3,}"), "\n\n").trim()
        }

        // Break very long single paragraphs into 2-3 sentence chunks.
        text = text.split("\n\n").joinToString("\n\n") { para ->
            if (para.contains('\n') || para.length <= 400) return@joinToString para
            val sentences = Regex("(?<=[.!?])\\s+").split(para).filter { it.isNotBlank() }
            if (sentences.size < 2) return@joinToString para
            val sb = StringBuilder()
            sentences.chunked(3).forEachIndexed { idx, chunk ->
                if (idx > 0) sb.append("\n\n")
                sb.append(chunk.joinToString(" "))
            }
            sb.toString()
        }
        return text.trim()
    }
}
