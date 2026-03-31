package io.github.koogcompose.prompt

class PromptStack(
    private val enforced: List<String>,
    private val default: List<String>,
    private val session: List<String>,
    private val user: List<String>
) {
    fun resolve(): String = buildString {
        if (enforced.isNotEmpty()) {
            appendLine("# ENFORCED RULES — These cannot be overridden.")
            enforced.forEach { appendLine(it) }
            appendLine()
        }
        if (default.isNotEmpty()) {
            appendLine("# ASSISTANT IDENTITY")
            default.forEach { appendLine(it) }
            appendLine()
        }
        if (session.isNotEmpty()) {
            appendLine("# SESSION CONTEXT")
            session.forEach { appendLine(it) }
            appendLine()
        }
        if (user.isNotEmpty()) {
            appendLine("# USER INSTRUCTIONS")
            user.forEach { appendLine(it) }
        }
    }.trim()

    fun withSessionContext(context: String): PromptStack = PromptStack(
        enforced = enforced,
        default = default,
        session = session + context,
        user = user
    )

    fun withUserInstructions(instructions: String): PromptStack = PromptStack(
        enforced = enforced,
        default = default,
        session = session,
        user = user + instructions
    )

    val hasEnforcedRules: Boolean get() = enforced.isNotEmpty()

    override fun toString(): String = resolve()

    class Builder {
        private val enforced = mutableListOf<String>()
        private val default = mutableListOf<String>()
        private val session = mutableListOf<String>()
        private val user = mutableListOf<String>()

        fun enforce(block: () -> String) { enforced.add(block()) }
        fun default(block: () -> String) { default.add(block()) }
        fun session(block: () -> String) { session.add(block()) }
        fun user(block: () -> String) { user.add(block()) }

        fun build(): PromptStack = PromptStack(
            enforced = enforced.toList(),
            default = default.toList(),
            session = session.toList(),
            user = user.toList()
        )
    }

    companion object {
        operator fun invoke(block: Builder.() -> Unit): PromptStack =
            Builder().apply(block).build()

        val Empty: PromptStack = PromptStack(
            enforced = emptyList(),
            default = emptyList(),
            session = emptyList(),
            user = emptyList()
        )
    }
}
