package io.github.koogcompose.tool


/**
 * Resolves [ToolName] placeholders in instruction strings to their full schema.
 *
 * When you write:
 * ```kotlin
 * phase("payment") {
 *     instructions {
 *         """
 *         Help the user send money.
 *         Use [GetBalance] to check funds before sending.
 *         Use [SendMoney] to execute the transfer.
 *         Always confirm before calling [SendMoney].
 *         """.trimIndent()
 *     }
 * }
 * ```
 *
 * `[GetBalance]` is expanded to:
 * ```
 * `get_balance`: Retrieves the current account balance for the authenticated user.
 *   Parameters:
 *     - account_id (String, required): The account to query.
 *     - currency (String, optional): ISO currency code. Defaults to KES.
 * ```
 *
 * This gives the LLM precise knowledge of what tools it has and how to call them,
 * without you repeating tool docs in every instruction block.
 */
object ToolRefResolver {

    private val TOOL_REF_REGEX = Regex("""\[([A-Za-z][A-Za-z0-9_]*)]""")

    /**
     * Resolves all [ToolName] references in [instructions] against [registry].
     *
     * References to tools not found in the registry are left as-is with a
     * warning suffix so you catch mismatches at dev time.
     */
    fun resolve(instructions: String, registry: ToolRegistry): String {
        return TOOL_REF_REGEX.replace(instructions) { match ->
            val toolName = match.groupValues[1]
            val tool = registry.findBySimpleName(toolName)
            tool?.toInlineSchema() ?: // Leave as-is but flag so devs notice the broken ref
            "[${toolName} ⚠ not registered]"
        }
    }
}

/**
 * Looks up a tool by its simple class name (e.g. "GetBalance" matches a tool
 * whose class is named `GetBalanceTool` or whose [SecureTool.name] is "get_balance"
 * or "GetBalance").
 *
 * Matching order:
 * 1. Exact match on [SecureTool.name]
 * 2. Case-insensitive match on [SecureTool.name] (snake_case → PascalCase)
 * 3. Simple class name match (strips "Tool" suffix)
 */
internal fun ToolRegistry.findBySimpleName(ref: String): SecureTool? {
    // 1. Exact
    all.firstOrNull { it.name == ref }?.let { return it }

    // 2. PascalCase → snake_case conversion (GetBalance → get_balance)
    val asSnake = ref.toSnakeCase()
    all.firstOrNull { it.name.equals(asSnake, ignoreCase = true) }?.let { return it }

    // 3. Class name match — strips "Tool" suffix from the ref ("GetBalanceTool" → "GetBalance")
    val stripped = ref.removeSuffix("Tool")
    all.firstOrNull { it.name.equals(stripped.toSnakeCase(), ignoreCase = true) }?.let { return it }

    return null
}

/**
 * Renders a [SecureTool] as an inline schema block suitable for embedding
 * inside an LLM system prompt.
 *
 * Output format:
 * ```
 * `get_balance` [SAFE]: Retrieves the current account balance.
 *   Parameters:
 *     - account_id (String, required): The account identifier.
 *     - currency (String, optional): ISO currency code. Defaults to KES.
 * ```
 */
internal fun SecureTool.toInlineSchema(): String = buildString {
    // Header: name, permission badge, description
    append("`${name}` [${permissionLevel.name}]: ${description}")

    // Parameters (if the tool exposes a schema)
    val params = describeParameters()
    if (params.isNotEmpty()) {
        appendLine()
        append("  Parameters:")
        params.forEach { param ->
            appendLine()
            val requiredLabel = if (param.required) "required" else "optional"
            append("    - ${param.name} (${param.type}, ${requiredLabel}): ${param.description}")
            if (param.default != null) append(". Defaults to ${param.default}")
        }
    }
}

/**
 * Converts PascalCase or camelCase to snake_case.
 * "GetBalance" → "get_balance", "sendMoney" → "send_money"
 */
private fun String.toSnakeCase(): String =
    replace(Regex("([A-Z])")) { "_${it.value.lowercase()}" }
        .trimStart('_')
