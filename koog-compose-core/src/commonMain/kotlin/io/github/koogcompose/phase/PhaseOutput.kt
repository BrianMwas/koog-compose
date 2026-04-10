package io.github.koogcompose.phase

import ai.koog.prompt.structure.json.JsonStructure
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer

/**
 * Describes the expected structured output type for a phase.
 * Existentially typed (PhaseOutput<*>) so Phase and PhaseRegistry
 * don't need to carry the output type parameter.
 *
 * @param version Schema version for evolving output types. Increment when
 *   the data class structure changes (fields added/removed/renamed).
 *   Useful for analytics tracking and migration of persisted outputs.
 */
public class PhaseOutput<O>(
    public val serializer: KSerializer<O>,
    public val structure: JsonStructure<O>,
    public val retries: Int,
    public val version: Int = 1,
    public val validate: (O) -> ValidationResult = { ValidationResult.Valid },
) {
    /**
     * Parses raw LLM output into [O], with structural validation.
     *
     * 1. Extracts JSON from the raw string (strips markdown fences if present)
     * 2. Parses via Koog's JsonStructure
     * 3. Runs the user-provided [validate] lambda
     * 4. Returns the result or throws [SerializationException] on failure
     */
    public fun parse(raw: String): O {
        val cleaned = raw.trim()
            .removeSurroundingMarkdownFences()

        val result = structure.parse(cleaned)
        val validation = validate(result)
        if (validation is ValidationResult.Invalid) {
            throw SerializationException("Validation failed: ${validation.reason}")
        }
        return result
    }
}

/** Result of a structured output validation check. */
public sealed class ValidationResult {
    public object Valid : ValidationResult()
    public data class Invalid(val reason: String) : ValidationResult()
}

/** Strips ```json ... ``` or ``` ... ``` fences if present. */
private fun String.removeSurroundingMarkdownFences(): String {
    val trimmed = this.trim()
    return when {
        trimmed.startsWith("```json") && trimmed.endsWith("```") ->
            trimmed.removePrefix("```json").removeSuffix("```").trim()
        trimmed.startsWith("```") && trimmed.endsWith("```") ->
            trimmed.removePrefix("```").removeSuffix("```").trim()
        else -> trimmed
    }
}

/**
 * Creates a [PhaseOutput] for type [O].
 * Examples are always injected into the prompt regardless of whether
 * the provider supports native JSON Schema — this primes the model's
 * context toward the expected shape, not just token-level constraints.
 *
 * @param version Schema version. Increment when the data class structure
 *   changes (fields added/removed/renamed). Included in the schema hint
 *   for analytics and migration tracking.
 * @param validate Optional validation lambda. Return [ValidationResult.Invalid]
 *   with a reason to trigger a retry. The reason is fed back to the LLM so it
 *   can self-correct on the next attempt.
 */
public inline fun <reified O> phaseOutput(
    retries: Int = 3,
    version: Int = 1,
    examples: List<O> = emptyList(),
    descriptionOverrides: Map<String, String> = emptyMap(),
    excludedProperties: Set<String> = emptySet(),
    noinline validate: (O) -> ValidationResult = { ValidationResult.Valid },
): PhaseOutput<O> {
    val structure = JsonStructure.create<O>(
        examples = examples,
        descriptionOverrides = descriptionOverrides,
        excludedProperties = excludedProperties,
    )
    return PhaseOutput(serializer<O>(), structure, retries, version, validate)
}