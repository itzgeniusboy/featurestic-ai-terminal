package com.txdost

// A "skill" is a small instruction block appended to the system prompt.
// Built-in skills ship with the app; users can also add custom skills.
data class Skill(
    val id: String,
    val name: String,
    val description: String = "",
    val instruction: String,
    val builtIn: Boolean = false,
    val enabled: Boolean = false
)

object BuiltInSkills {
    val all: List<Skill> = listOf(
        Skill(
            id = "concise",
            name = "Concise replies",
            description = "Short, clean answers — no walls of text.",
            instruction = "Keep every reply short and clean. Do NOT send long, unnecessary messages. " +
                "Prefer 1-4 lines unless the user asks for detail. No filler, no repetition, no restating the question.",
            builtIn = true,
            enabled = true
        ),
        Skill(
            id = "plan_first",
            name = "Plan first",
            description = "Give a short plan before doing the work.",
            instruction = "Before doing any work, give a 2-3 line plan. Then act. Keep it brief.",
            builtIn = true
        ),
        Skill(
            id = "explain",
            name = "Explain briefly",
            description = "Add a one-line explanation with code.",
            instruction = "When you write code, add a single short line explaining what it does. Never long explanations.",
            builtIn = true
        ),
        Skill(
            id = "safe_files",
            name = "Safe file ops",
            description = "Ask before deleting or overwriting files.",
            instruction = "Before deleting or overwriting a file, ask the user to confirm first.",
            builtIn = true
        ),
        Skill(
            id = "roman_urdu",
            name = "Roman Urdu replies",
            description = "Reply in Roman Urdu / Hinglish.",
            instruction = "Always reply in Roman Urdu (Hinglish). Keep technical terms (commands, paths, code) in English.",
            builtIn = true
        ),
        Skill(
            id = "plain_text",
            name = "Plain text",
            description = "No markdown, no code fences.",
            instruction = "Reply in plain text only. Do NOT use markdown, headings, bold, or bullet lists. " +
                "Exception: when the user asks for code, put the full code in ONE fenced block (```) on its own " +
                "lines so it renders as a copyable code box; keep any explanation outside the block in 1-2 plain lines.",
            builtIn = true
        ),
        Skill(
            id = "ask_before_run",
            name = "Ask before running",
            description = "Confirm before every command.",
            instruction = "Before running any command via an action tag, first ask the user to confirm. Only run after the user says yes.",
            builtIn = true
        ),
        Skill(
            id = "code_only",
            name = "Code only",
            description = "Output just the code, minimal words.",
            instruction = "When the user asks for code, output ONLY the code with minimal words. No long explanations.",
            builtIn = true
        )
    )
}
