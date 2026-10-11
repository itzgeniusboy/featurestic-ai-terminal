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

    // ── Code "safety net" heuristics (for weak models that forget fences) ──
    private val codeStartRe = Regex(
        "^(def|class|import|from|function|const|let|var|fun|val|public|private|protected|static|" +
            "void|int|float|double|bool|boolean|String|return|if|else|elif|for|while|switch|case|break|" +
            "continue|try|catch|finally|throw|new|package|using|struct|enum|interface|extends|implements|" +
            "async|await|export|require|print|printf|puts|echo|sudo|npm|npx|yarn|pip|pip3|docker|git|cd|ls|" +
            "cat|mkdir|rm|cp|mv|curl|wget|python|python3|node|bash|sh|export|source|set)\\b.*"
    )
    private val shebangRe = Regex("^#!.*")
    private val htmlTagRe = Regex("^</?[A-Za-z][\\w:-]*(\\s|/?>)")

    private fun isCodeLike(line: String): Boolean {
        val s = line.trimEnd()
        if (s.isBlank()) return false
        if (s.length - s.trimStart().length >= 2) return true
        if (s.endsWith(";") || s.endsWith("{") || s.endsWith("}") || s.endsWith(")")) return true
        val t = s.trimStart()
        if (codeStartRe.matches(t)) return true
        if (shebangRe.matches(t)) return true
        if (htmlTagRe.containsMatchIn(t)) return true
        if (t.startsWith("$ ")) return true
        if (t.contains("=>") || t.contains("->") || t.contains("::")) return true
        if (t.contains("){") || t.contains("};")) return true
        return false
    }

    private fun looksLikeCode(lines: List<String>): Boolean {
        val nonBlank = lines.filter { it.isNotBlank() }
        if (nonBlank.size < 2) return false
        val code = nonBlank.count { isCodeLike(it) }
        if (nonBlank.size == 2) return code == 2
        return code * 100 / nonBlank.size >= 70
    }

    private fun guessLanguage(lines: List<String>): String {
        val j = lines.joinToString("\n")
        return when {
            j.contains("#!") || Regex("(?m)^\\s*\\$\\s").containsMatchIn(j) ||
                Regex("(?m)^\\s*(cd|ls|cat|mkdir|rm|cp|mv|curl|wget|git|npm|npx|yarn|pip|pip3|sudo|chmod|echo|export)\\b").containsMatchIn(j) -> "shell"
            Regex("(?m)^\\s*(def|import|from)\\s").containsMatchIn(j) || j.contains("print(") -> "python"
            j.contains("<?php") -> "php"
            j.contains("#include") -> "c"
            Regex("(?m)^\\s*(fun|val)\\s").containsMatchIn(j) -> "kotlin"
            j.contains("public ") || j.contains("void ") || j.contains("System.out") -> "java"
            j.contains("console.log") || j.contains("=>") ||
                Regex("(?m)^\\s*(const|let|function)\\s").containsMatchIn(j) -> "javascript"
            htmlTagRe.containsMatchIn(j) -> "html"
            else -> ""
        }
    }

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
            if (!single) {
                val paraLines = t.split('\n')
                if (looksLikeCode(paraLines)) {
                    blocks.add(MdBlock(MdType.CODE, TextUtils.stripEmoji(t), language = guessLanguage(paraLines)))
                    return
                }
            }
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
        return mergeAdjacentCode(blocks)
    }

    /** Fuse code blocks that end up directly adjacent (e.g. safety-net split by a blank line). */
    private fun mergeAdjacentCode(blocks: List<MdBlock>): List<MdBlock> {
        val merged = mutableListOf<MdBlock>()
        for (b in blocks) {
            val last = merged.lastOrNull()
            if (b.type == MdType.CODE && last?.type == MdType.CODE) {
                merged[merged.size - 1] = last.copy(
                    text = last.text + "\n" + b.text,
                    language = last.language.ifEmpty { b.language }
                )
            } else merged.add(b)
        }
        return merged
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
