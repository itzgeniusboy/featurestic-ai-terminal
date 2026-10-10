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
        )
    )
}
