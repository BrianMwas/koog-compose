package io.github.koogcompose.workflow

import io.github.koogcompose.layout.UserRole
import io.github.koogcompose.layout.WorkflowId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runtime store for [WorkflowDefinition]s, keyed by [WorkflowId].
 *
 * Thread-safe via a [Mutex]. Definitions are exposed as a [StateFlow] so consumers
 * can react when definitions change at runtime (e.g., an OTA push lands mid-session).
 *
 * Semantics:
 * - Registering a definition whose id already exists REPLACES the old one (OTA update).
 * - Active sessions hold a snapshot reference — they see the OLD definition until the
 *   next session start. Hot-swapping mid-session is deliberately unsupported.
 * - The registry validates that every [UserRoleId] in [WorkflowDefinition.defaultLayouts]
 *   resolves via [roleResolver] so JSON-loaded workflows can't silently reference roles
 *   the host forgot to register.
 */
public class WorkflowRegistry(
    private val roleResolver: (UserRoleId) -> UserRole?,
) {
    private val mutex = Mutex()
    private val _definitions = MutableStateFlow<Map<WorkflowId, WorkflowDefinition>>(emptyMap())

    public val definitions: StateFlow<Map<WorkflowId, WorkflowDefinition>> =
        _definitions.asStateFlow()

    public suspend fun register(definition: WorkflowDefinition) {
        mutex.withLock {
            validateRoles(definition)
            _definitions.value = _definitions.value + (definition.id to definition)
        }
    }

    public suspend fun registerAll(definitions: Iterable<WorkflowDefinition>) {
        mutex.withLock {
            definitions.forEach { validateRoles(it) }
            _definitions.value = _definitions.value + definitions.associateBy { it.id }
        }
    }

    public suspend fun unregister(id: WorkflowId) {
        mutex.withLock {
            _definitions.value = _definitions.value - id
        }
    }

    public fun get(id: WorkflowId): WorkflowDefinition? = _definitions.value[id]

    public fun require(id: WorkflowId): WorkflowDefinition =
        get(id) ?: error("No workflow registered with id '${id.value}'")

    public fun all(): Collection<WorkflowDefinition> = _definitions.value.values

    private fun validateRoles(definition: WorkflowDefinition) {
        for (roleId in definition.defaultLayouts.keys) {
            requireNotNull(roleResolver(roleId)) {
                "Workflow '${definition.id.value}' references unknown role '${roleId.value}'. " +
                    "Register the role before loading this workflow."
            }
        }
    }
}
