package io.github.koogcompose.tool

/**
 * The result of a [SecureTool.validateArgs] call.
 */
public sealed class ValidationResult {
    /** Args are valid — proceed to execution. */
    public object Valid : ValidationResult()

    /**
     * Args are invalid — execution is blocked.
     * @param reason Human-readable explanation surfaced as [ToolResult.Failure].
     */
    public data class Invalid(val reason: String) : ValidationResult()
}