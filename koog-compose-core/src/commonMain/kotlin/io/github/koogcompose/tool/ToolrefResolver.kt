package io.github.koogcompose.tool

import kotlin.jvm.JvmInline


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
public object ToolRefResolver {

    private val TOOL_REF_REGEX = Regex("""\[([A-Za-z][A-Za-z0-9_]*)]""")
    private val TYPED_TOOL_REF_REGEX = Regex("""«tool-ref:([A-Za-z][A-Za-z0-9_.]*)»""")

    /**
     * Resolves all `[ToolName]` and `«tool-ref:T»` references in [instructions]
     * against [registry].
     *
     * **Throws [UnresolvedToolRefException]** if any reference cannot be resolved.
     * This is a build-time failure — no silent warnings.
     *
     * @param phaseName Optional phase name for better error messages.
     * @param strict When false, unresolved refs get a warning suffix instead of throwing.
     */
    public fun resolve(
        instructions: String,
        registry: ToolRegistry,
        phaseName: String? = null,
        strict: Boolean = true
    ): String {
        val unresolved = mutableListOf<String>()

        // First pass: resolve typed refs «tool-ref:T»
        var result = TYPED_TOOL_REF_REGEX.replace(instructions) { match ->
            val typeName = match.groupValues[1]
            val tool = registry.findBySimpleName(typeName)
            if (tool != null) {
                tool.toInlineSchema()
            } else {
                unresolved.add(typeName)
                if (strict) match.value else "[${typeName} ⚠ not registered]"
            }
        }

        // Second pass: resolve string refs [ToolName]
        result = TOOL_REF_REGEX.replace(result) { match ->
            val toolName = match.groupValues[1]
            val tool = registry.findBySimpleName(toolName)
            if (tool != null) {
                tool.toInlineSchema()
            } else {
                unresolved.add(toolName)
                if (strict) match.value else "[${toolName} ⚠ not registered]"
            }
        }

        if (strict && unresolved.isNotEmpty()) {
            val location = phaseName?.let { " in phase \"$it\"" } ?: ""
            val registered = registry.all.map { it.name }.joinToString(", ")
            throw UnresolvedToolRefException(
                unresolvedRefs = unresolved,
                phaseName = phaseName,
                message = "Unresolved tool reference(s)$location: ${unresolved.joinToString(", ")}. " +
                    "Registered tools: [$registered]"
            )
        }

        return result
    }

    /**
     * Validates all tool references in [instructions] without expanding them.
     * Returns a list of unresolved tool names, or empty list if all resolve.
     */
    public fun validate(
        instructions: String,
        registry: ToolRegistry
    ): List<String> {
        val unresolved = mutableListOf<String>()
        TOOL_REF_REGEX.findAll(instructions).forEach { match ->
            val toolName = match.groupValues[1]
            if (registry.findBySimpleName(toolName) == null) {
                unresolved.add(toolName)
            }
        }
        TYPED_TOOL_REF_REGEX.findAll(instructions).forEach { match ->
            val typeName = match.groupValues[1]
            if (registry.findBySimpleName(typeName) == null) {
                unresolved.add(typeName)
            }
        }
        return unresolved
    }
}

/**
 * Thrown when a `[ToolName]` or `«tool-ref:T»` reference cannot be resolved
 * against the effective tool registry.
 *
 * This is a **build-time failure** — it prevents the app from compiling
 * with broken tool references.
 */
public class UnresolvedToolRefException(
    public val unresolvedRefs: List<String>,
    public val phaseName: String?,
    message: String
) : IllegalArgumentException(message)

/**
 * Type-safe tool reference for use in phase instruction strings.
 *
 * Instead of relying on string-based `[ToolName]` syntax (which has no
 * compiler support), use `toolRef<T>()` for compile-time safety:
 *
 * ```kotlin
 * phase("payment") {
 *     instructions {
 *         "Use ${toolRef<GetBalanceTool>()} to check funds before ${toolRef<SendMoneyTool>()}."
 *     }
 *     tool(GetBalanceTool())
 *     tool(SendMoneyTool())
 * }
 * ```
 *
 * - **Compile-time error** if `T` isn't a subtype of [SecureTool]
 * - **Build-time error** if the tool isn't registered in the phase (via [ToolRefResolver.resolve])
 *
 * @param T The tool type — must be a subtype of [SecureTool].
 * @return A marker string that [ToolRefResolver] expands to the tool's inline schema.
 */
public inline fun <reified T : SecureTool> toolRef(): String =
    "«tool-ref:${T::class.simpleName ?: error("toolRef requires a named class")}»"

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
