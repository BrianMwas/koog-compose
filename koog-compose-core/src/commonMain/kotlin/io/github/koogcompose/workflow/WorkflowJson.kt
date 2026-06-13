package io.github.koogcompose.workflow

import io.github.koogcompose.layout.ComponentId
import io.github.koogcompose.layout.ComponentProps
import io.github.koogcompose.layout.Position
import io.github.koogcompose.layout.RateLimit
import io.github.koogcompose.layout.SlotAccess
import io.github.koogcompose.layout.SlotId
import io.github.koogcompose.layout.UserRole
import io.github.koogcompose.layout.WorkflowId
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire-format encode/decode for [WorkflowDefinition].
 *
 * Separate from the in-memory model so:
 * - JSON shape is stable and versioned independently of the Kotlin types.
 * - Loading goes through the same DSL builders as code-defined workflows — both
 *   get identical init-time validation.
 * - [io.github.koogcompose.layout.LayoutInvariant]s (arbitrary functions) are silently
 *   dropped during decode: they cannot be expressed in a wire format. OTA workflows
 *   that need invariants must reference them by id from a host-registered catalogue
 *   (deferred to a future layer).
 *
 * **Schema versioning**: bump [SCHEMA_VERSION] on any breaking change. [decode] throws
 * [WorkflowJsonException] when the document's version doesn't match.
 */
public object WorkflowJson {
    public const val SCHEMA_VERSION: Int = 1

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    public fun encode(definition: WorkflowDefinition): String =
        json.encodeToString(WorkflowDefinitionDto.serializer(), definition.toDto())

    /**
     * Decodes JSON into a [WorkflowDefinition] via the DSL builders.
     *
     * [roleResolver] maps role-id strings to [UserRole] instances. Throws
     * [WorkflowJsonException] on any decoding or validation failure.
     */
    public fun decode(
        rawJson: String,
        roleResolver: (UserRoleId) -> UserRole?,
    ): WorkflowDefinition {
        val dto = try {
            json.decodeFromString(WorkflowDefinitionDto.serializer(), rawJson)
        } catch (t: Throwable) {
            throw WorkflowJsonException("Malformed workflow JSON: ${t.message}", t)
        }
        if (dto.schemaVersion != SCHEMA_VERSION) {
            throw WorkflowJsonException(
                "Unsupported workflow schema version ${dto.schemaVersion}; expected $SCHEMA_VERSION"
            )
        }
        return dto.toDefinition(roleResolver)
    }
}

public class WorkflowJsonException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

// ── Wire DTOs ─────────────────────────────────────────────────────────────────

@Serializable
internal data class WorkflowDefinitionDto(
    val schemaVersion: Int = WorkflowJson.SCHEMA_VERSION,
    val id: String,
    val displayName: String,
    val description: String = "",
    val phases: List<WorkflowPhaseDto>,
    val initialPhase: String,
    val defaultLayouts: Map<String, DefaultLayoutDto> = emptyMap(),
    val policy: WorkflowPolicyDto = WorkflowPolicyDto(),
    val triggers: List<TriggerDto> = emptyList(),
)

@Serializable
internal data class WorkflowPhaseDto(
    val id: String,
    val displayName: String = "",
    val systemPrompt: String,
    val permittedDirectiveTypes: Set<String> = setOf("Show", "Hide", "Reorder", "Swap", "Lock"),
    val transitions: List<TransitionDto> = emptyList(),
)

@Serializable
internal data class TransitionDto(
    val toPhase: String,
    val condition: TransitionConditionDto,
)

@Serializable
internal data class TransitionConditionDto(
    val type: String,
    val reasonContains: String? = null,
    val actionId: String? = null,
    val signalKey: String? = null,
    val equals: String? = null,
)

@Serializable
internal data class DefaultLayoutDto(
    val placements: List<DefaultPlacementDto>,
)

@Serializable
internal data class DefaultPlacementDto(
    val componentId: String,
    val slotId: String,
    val position: PositionDto = PositionDto("End"),
    val props: Map<String, String> = emptyMap(),
)

@Serializable
internal data class PositionDto(
    val type: String,
    val index: Int? = null,
    val reference: String? = null,
)

@Serializable
internal data class WorkflowPolicyDto(
    val slotAccessOverrides: Map<String, Map<String, String>> = emptyMap(),
    val componentRoleOverrides: Map<String, Set<String>> = emptyMap(),
    val rateLimit: RateLimitDto = RateLimitDto(),
)

@Serializable
internal data class RateLimitDto(
    val perTurn: Int = 20,
    val perSession: Int = 500,
    val coalescingWindowMs: Long = 16,
)

@Serializable
internal data class TriggerDto(
    val id: String,
    val displayName: String = "",
    val activeInPhases: Set<String>,
    val source: TriggerSourceDto,
)

@Serializable
internal data class TriggerSourceDto(
    val type: String,
    val intervalSeconds: Int? = null,
    val actionId: String? = null,
    val signalKey: String? = null,
)

// ── Encoding: model → DTO ─────────────────────────────────────────────────────

private fun WorkflowDefinition.toDto() = WorkflowDefinitionDto(
    id             = id.value,
    displayName    = displayName,
    description    = description,
    phases         = phases.map { it.toDto() },
    initialPhase   = initialPhase.value,
    defaultLayouts = defaultLayouts.mapKeys { it.key.value }.mapValues { it.value.toDto() },
    policy         = policy.toDto(),
    triggers       = triggers.map { it.toDto() },
)

private fun WorkflowPhase.toDto() = WorkflowPhaseDto(
    id                     = id.value,
    displayName            = displayName,
    systemPrompt           = systemPrompt,
    permittedDirectiveTypes = permittedDirectiveTypes.map { it.name }.toSet(),
    transitions            = transitions.map { it.toDto() },
)

private fun PhaseTransition.toDto() = TransitionDto(toPhase.value, condition.toDto())

private fun TransitionCondition.toDto(): TransitionConditionDto = when (this) {
    is TransitionCondition.OnAgentReason ->
        TransitionConditionDto(type = "OnAgentReason", reasonContains = reasonContains)
    is TransitionCondition.OnUserAction ->
        TransitionConditionDto(type = "OnUserAction", actionId = actionId)
    is TransitionCondition.OnDataChange ->
        TransitionConditionDto(type = "OnDataChange", signalKey = signalKey, equals = equals)
    TransitionCondition.Always ->
        TransitionConditionDto(type = "Always")
}

private fun DefaultLayout.toDto() = DefaultLayoutDto(placements.map { it.toDto() })

private fun DefaultPlacement.toDto() = DefaultPlacementDto(
    componentId = componentId.value,
    slotId      = slotId.value,
    position    = position.toDto(),
    props       = props.values,
)

private fun Position.toDto(): PositionDto = when (this) {
    Position.Start         -> PositionDto("Start")
    Position.End           -> PositionDto("End")
    is Position.Index      -> PositionDto("Index", index = index)
    is Position.Before     -> PositionDto("Before", reference = reference.value)
    is Position.After      -> PositionDto("After", reference = reference.value)
}

private fun io.github.koogcompose.layout.WorkflowPolicy.toDto() = WorkflowPolicyDto(
    slotAccessOverrides = slotAccessOverrides
        .mapKeys { it.key.id }
        .mapValues { (_, map) -> map.mapKeys { it.key.value }.mapValues { it.value.name } },
    componentRoleOverrides = componentRoleOverrides
        .mapKeys { it.key.value }
        .mapValues { (_, roles) -> roles.map { it.id }.toSet() },
    rateLimit = RateLimitDto(
        perTurn            = rateLimit.perTurn,
        perSession         = rateLimit.perSession,
        coalescingWindowMs = rateLimit.coalescingWindow.inWholeMilliseconds,
    ),
)

private fun TriggerDefinition.toDto() = TriggerDto(
    id             = id.value,
    displayName    = displayName,
    activeInPhases = activeInPhases.map { it.value }.toSet(),
    source         = source.toDto(),
)

private fun TriggerSource.toDto(): TriggerSourceDto = when (this) {
    is TriggerSource.Interval    -> TriggerSourceDto("Interval", intervalSeconds = intervalSeconds)
    is TriggerSource.UserAction  -> TriggerSourceDto("UserAction", actionId = actionId)
    is TriggerSource.DataChange  -> TriggerSourceDto("DataChange", signalKey = signalKey)
}

// ── Decoding: DTO → model via DSL builders ────────────────────────────────────

private fun WorkflowDefinitionDto.toDefinition(
    roleResolver: (UserRoleId) -> UserRole?,
): WorkflowDefinition = workflow(WorkflowId(id)) {
    displayName  = this@toDefinition.displayName
    description  = this@toDefinition.description
    initialPhase = PhaseId(this@toDefinition.initialPhase)

    for (phaseDto in phases) {
        phase(PhaseId(phaseDto.id)) {
            displayName  = phaseDto.displayName
            systemPrompt = phaseDto.systemPrompt
            val types = phaseDto.permittedDirectiveTypes
                .mapNotNull { name -> runCatching { DirectiveType.valueOf(name) }.getOrNull() }
            if (types.isNotEmpty()) permittedDirectives(*types.toTypedArray())
            phaseDto.transitions.forEach { t ->
                transition(toPhase = PhaseId(t.toPhase), on = t.condition.toDomain())
            }
        }
    }

    for ((roleIdStr, layoutDto) in defaultLayouts) {
        val role = roleResolver(UserRoleId(roleIdStr))
            ?: throw WorkflowJsonException(
                "Workflow '$id' references unknown role '$roleIdStr' in defaultLayouts"
            )
        defaultLayout(role) {
            layoutDto.placements.forEach { p ->
                place(
                    componentId = ComponentId(p.componentId),
                    `in_`       = SlotId(p.slotId),
                    position    = p.position.toDomain(),
                    props       = ComponentProps(p.props),
                )
            }
        }
    }

    for (triggerDto in triggers) {
        trigger(TriggerId(triggerDto.id)) {
            displayName = triggerDto.displayName
            activeIn(*triggerDto.activeInPhases.map { PhaseId(it) }.toTypedArray())
            triggerDto.source.applyTo(this, triggerDto.id)
        }
    }

    policy {
        for ((roleIdStr, slotMap) in this@toDefinition.policy.slotAccessOverrides) {
            val role = roleResolver(UserRoleId(roleIdStr))
                ?: throw WorkflowJsonException(
                    "Workflow '$id' slot policy references unknown role '$roleIdStr'"
                )
            for ((slotStr, accessStr) in slotMap) {
                val access = runCatching { SlotAccess.valueOf(accessStr) }.getOrNull()
                    ?: throw WorkflowJsonException("Unknown SlotAccess '$accessStr'")
                narrowSlotAccess(role, SlotId(slotStr), access)
            }
        }
        for ((compIdStr, roleIds) in this@toDefinition.policy.componentRoleOverrides) {
            val roles = roleIds.map { rid ->
                roleResolver(UserRoleId(rid))
                    ?: throw WorkflowJsonException(
                        "Workflow '$id' componentRoleOverrides references unknown role '$rid'"
                    )
            }.toSet()
            restrictComponentToRoles(ComponentId(compIdStr), roles)
        }
        rateLimit = RateLimit(
            perTurn            = this@toDefinition.policy.rateLimit.perTurn,
            perSession         = this@toDefinition.policy.rateLimit.perSession,
            coalescingWindow   = this@toDefinition.policy.rateLimit.coalescingWindowMs.milliseconds,
        )
    }
}

private fun TriggerSourceDto.applyTo(builder: TriggerBuilder, triggerId: String) {
    when (type) {
        "Interval"   -> builder.fromInterval(
            intervalSeconds ?: throw WorkflowJsonException("Trigger '$triggerId' Interval missing intervalSeconds")
        )
        "UserAction" -> builder.fromUserAction(
            actionId ?: throw WorkflowJsonException("Trigger '$triggerId' UserAction missing actionId")
        )
        "DataChange" -> builder.fromDataChange(
            signalKey ?: throw WorkflowJsonException("Trigger '$triggerId' DataChange missing signalKey")
        )
        else -> throw WorkflowJsonException("Trigger '$triggerId' unknown source type '$type'")
    }
}

private fun TransitionConditionDto.toDomain(): TransitionCondition = when (type) {
    "OnAgentReason" -> TransitionCondition.OnAgentReason(
        reasonContains ?: throw WorkflowJsonException("OnAgentReason missing reasonContains")
    )
    "OnUserAction"  -> TransitionCondition.OnUserAction(
        actionId ?: throw WorkflowJsonException("OnUserAction missing actionId")
    )
    "OnDataChange"  -> TransitionCondition.OnDataChange(
        signalKey = signalKey ?: throw WorkflowJsonException("OnDataChange missing signalKey"),
        equals    = equals    ?: throw WorkflowJsonException("OnDataChange missing equals"),
    )
    "Always"        -> TransitionCondition.Always
    else            -> throw WorkflowJsonException("Unknown transition condition type '$type'")
}

private fun PositionDto.toDomain(): Position = when (type) {
    "Start"  -> Position.Start
    "End"    -> Position.End
    "Index"  -> Position.Index(index ?: throw WorkflowJsonException("Position.Index missing index"))
    "Before" -> Position.Before(
        io.github.koogcompose.layout.ComponentId(
            reference ?: throw WorkflowJsonException("Position.Before missing reference")
        )
    )
    "After"  -> Position.After(
        io.github.koogcompose.layout.ComponentId(
            reference ?: throw WorkflowJsonException("Position.After missing reference")
        )
    )
    else     -> throw WorkflowJsonException("Unknown Position type '$type'")
}
