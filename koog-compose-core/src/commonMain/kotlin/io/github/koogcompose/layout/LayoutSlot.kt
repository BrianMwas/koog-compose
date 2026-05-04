package io.github.koogcompose.layout

import kotlin.jvm.JvmInline

/** Stable identifier for a slot — an addressing location the agent targets. */
@JvmInline
public value class SlotId(public val value: String) {
    init { require(value.isNotBlank()) { "SlotId must not be blank" } }
}

/** Stable identifier for a component — the things that flow through slots. */
@JvmInline
public value class ComponentId(public val value: String) {
    init { require(value.isNotBlank()) { "ComponentId must not be blank" } }
}

/**
 * Semantic tag attached to both slots and components. A slot declares which tags it
 * accepts; only components with at least one matching tag can be placed in it.
 */
@JvmInline
public value class Tag(public val value: String) {
    init { require(value.isNotBlank()) { "Tag must not be blank" } }
}

/**
 * Declares a layout slot. Created by the host app and registered in [SlotRegistry] at
 * startup. Slots are immutable — adding a new slot requires a new app version.
 */
public data class LayoutSlot(
    public val id: SlotId,
    public val capacity: SlotCapacity,
    public val allowedComponentTags: Set<Tag>,
    public val defaultBehavior: SlotDefault = SlotDefault.Empty,
) {
    init {
        require(allowedComponentTags.isNotEmpty()) {
            "LayoutSlot ${id.value} must declare at least one allowed tag"
        }
    }
}

/**
 * How many components a slot can hold simultaneously.
 *
 * - [Single]: exactly one; Show into a full slot evicts the existing component.
 * - [Multiple]: up to [Multiple.max]; Show into a full slot causes LRU eviction.
 * - [Unbounded]: no cap.
 */
public sealed class SlotCapacity {
    public data object Single : SlotCapacity()
    public data class Multiple(public val max: Int) : SlotCapacity() {
        init { require(max >= 2) { "Multiple capacity max must be >= 2; use Single for one" } }
    }
    public data object Unbounded : SlotCapacity()
}

/**
 * What a slot displays when empty.
 *
 * - [Empty]: render nothing.
 * - [Placeholder]: render the host-provided placeholder composable.
 * - [FallbackComponent]: render the named component with empty props. Must reference a
 *   registered component or validation will fail at startup.
 */
public sealed class SlotDefault {
    public data object Empty : SlotDefault()
    public data object Placeholder : SlotDefault()
    public data class FallbackComponent(public val componentId: ComponentId) : SlotDefault()
}

/**
 * Free-form, immutable bag of properties passed from the agent directive into the
 * rendered component. Kept as a string map so the serialization boundary is simple
 * (agent emits JSON strings, component reads what it needs).
 */
public data class ComponentProps(
    public val values: Map<String, String> = emptyMap(),
) {
    public companion object {
        public val Empty: ComponentProps = ComponentProps()
    }

    public fun get(key: String): String? = values[key]
    public fun getOrDefault(key: String, default: String): String = values[key] ?: default
}

/**
 * Host-app-declared registry of every slot the app supports.
 * Built at startup, immutable thereafter.
 */
public class SlotRegistry(slots: Iterable<LayoutSlot>) {
    private val slotList = slots.toList()
    private val bySlotId: Map<SlotId, LayoutSlot> = slotList.associateBy { it.id }.also { map ->
        require(map.size == slotList.size) { "SlotRegistry contains duplicate SlotIds" }
    }

    public val all: Collection<LayoutSlot> get() = bySlotId.values
    public operator fun get(id: SlotId): LayoutSlot? = bySlotId[id]
    public fun contains(id: SlotId): Boolean = id in bySlotId
    public fun require(id: SlotId): LayoutSlot =
        bySlotId[id] ?: throw IllegalArgumentException("Unknown SlotId: ${id.value}")
}

/**
 * Host-app-declared registry of every component the agent can reference.
 * Built at startup, immutable thereafter.
 *
 * @param requiredPermissions components are only shown to roles whose
 *   [UserRole.permissions] is a superset of this set — the first line of defense.
 */
public class ComponentRegistry(components: Iterable<ComponentDefinition>) {
    private val componentList = components.toList()
    private val byId: Map<ComponentId, ComponentDefinition> =
        componentList.associateBy { it.id }.also { map ->
            require(map.size == componentList.size) {
                "ComponentRegistry contains duplicate ComponentIds"
            }
        }

    public val all: Collection<ComponentDefinition> get() = byId.values
    public operator fun get(id: ComponentId): ComponentDefinition? = byId[id]
    public fun contains(id: ComponentId): Boolean = id in byId
    public fun require(id: ComponentId): ComponentDefinition =
        byId[id] ?: throw IllegalArgumentException("Unknown ComponentId: ${id.value}")

    /**
     * Cross-validates this registry against a [SlotRegistry]: every
     * [SlotDefault.FallbackComponent] must reference a registered component whose tags
     * intersect the slot's [LayoutSlot.allowedComponentTags].
     *
     * Call once at startup after both registries are built. Throws on the first violation.
     */
    public fun validateAgainst(slotRegistry: SlotRegistry) {
        slotRegistry.all.forEach { slot ->
            val default = slot.defaultBehavior
            if (default is SlotDefault.FallbackComponent) {
                val component = byId[default.componentId]
                    ?: error(
                        "Slot '${slot.id.value}' declares fallback component " +
                            "'${default.componentId.value}' which is not registered."
                    )
                require(component.tags.any { it in slot.allowedComponentTags }) {
                    "Fallback component '${component.id.value}' for slot '${slot.id.value}' " +
                        "has no tag matching the slot's allowed tags."
                }
            }
        }
    }
}

/**
 * Metadata for a component the agent can place. The [content] composable is resolved
 * by [io.github.koogcompose.layout.ui.LayoutHost] at render time via a
 * [ComponentContentProvider] registered in the UI layer.
 *
 * Keeping component metadata separate from the composable lambda lets the engine files
 * remain Compose-free and fully unit-testable.
 */
public data class ComponentDefinition(
    public val id: ComponentId,
    public val tags: Set<Tag>,
    public val requiredPermissions: Set<Permission> = emptySet(),
) {
    init { require(tags.isNotEmpty()) { "ComponentDefinition ${id.value} must declare at least one tag" } }

    override fun equals(other: Any?): Boolean = other is ComponentDefinition && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
