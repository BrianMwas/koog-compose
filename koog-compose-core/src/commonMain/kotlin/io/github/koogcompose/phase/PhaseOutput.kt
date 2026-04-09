package io.github.koogcompose.phase

import ai.koog.prompt.structure.json.JsonStructure
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Describes the expected structured output type for a phase.
 * Existentially typed (PhaseOutput<*>) so Phase and PhaseRegistry
 * don't need to carry the output type parameter.
 */
public class PhaseOutput<O>(
    public val serializer: KSerializer<O>,
    public val structure: JsonStructure<O>,
    public val retries: Int,
) {
    public fun parse(raw: String): O = structure.parse(raw)
}

/**
 * Creates a [PhaseOutput] for type [O].
 * Examples are always injected into the prompt regardless of whether
 * the provider supports native JSON Schema — this primes the model's
 * context toward the expected shape, not just token-level constraints.
 */
public inline fun <reified O> phaseOutput(
    retries: Int = 3,
    examples: List<O> = emptyList(),
    descriptionOverrides: Map<String, String> = emptyMap(),
    excludedProperties: Set<String> = emptySet(),
): PhaseOutput<O> {
    val structure = JsonStructure.create<O>(
        examples = examples,
        descriptionOverrides = descriptionOverrides,
        excludedProperties = excludedProperties,
    )
    return PhaseOutput(serializer<O>(), structure, retries)

}