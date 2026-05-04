package io.github.koogcompose.layout

import kotlin.jvm.JvmInline

/**
 * Immutable, session-scoped context describing WHO is using the app and WHAT workflow
 * they are running. Passed to [io.github.koogcompose.session.KoogComposeContext] at
 * session initialization and never mutated for the lifetime of the session.
 *
 * The combination of [businessId] + [activeWorkflow] is what the cloud config layer
 * (Layer 3) uses to look up the appropriate WorkflowDefinition.
 */
public data class WorkflowContext(
    public val businessId: BusinessId,
    public val userRole: UserRole,
    public val activeWorkflow: WorkflowId,
    public val deviceContext: DeviceContext,
    public val sessionMetadata: Map<String, String> = emptyMap(),
)

@JvmInline
public value class BusinessId(public val value: String) {
    init { require(value.isNotBlank()) { "BusinessId must not be blank" } }
}

@JvmInline
public value class WorkflowId(public val value: String) {
    init { require(value.isNotBlank()) { "WorkflowId must not be blank" } }
}

/**
 * Typed user role. Host apps subclass this once at startup and register every role
 * they support. The [permissions] set is the authoritative grant consulted by the
 * policy layer on every directive.
 *
 * Roles do not change mid-session. If a user's role changes, the host app starts a
 * new session with a new [WorkflowContext].
 */
public abstract class UserRole(
    public val id: String,
    public val displayName: String,
    public val permissions: Set<Permission>,
) {
    init { require(id.isNotBlank()) { "UserRole.id must not be blank" } }

    final override fun equals(other: Any?): Boolean = other is UserRole && other.id == id
    final override fun hashCode(): Int = id.hashCode()
    final override fun toString(): String = "UserRole($id)"
}

/**
 * A capability grant. Components carry [ComponentSpec.requiredPermissions] which the
 * policy layer compares against [UserRole.permissions].
 */
@JvmInline
public value class Permission(public val name: String) {
    init { require(name.isNotBlank()) { "Permission.name must not be blank" } }
}

/** Conventional permissions the SDK uses for its own engine-level checks. */
public object Permissions {
    public val Read: Permission = Permission("koog.read")
    public val Write: Permission = Permission("koog.write")
    public val Admin: Permission = Permission("koog.admin")
}

/**
 * Snapshot of the device the session is running on. Used by the agent to reason about
 * what UI is appropriate (denser layouts on tablets, simpler layouts when offline, etc.).
 */
public data class DeviceContext(
    public val platform: Platform,
    public val formFactor: FormFactor,
    public val networkClass: NetworkClass,
    public val locale: String,
)

public enum class Platform { Android, IOS, Desktop, Web }

public enum class FormFactor { Phone, Tablet, Desktop }

/**
 * Coarse-grained network classification. Important for markets where degraded/offline
 * operation is common — the agent reads this to decide whether to attempt cloud-bound
 * directives or stay strictly on-device.
 */
public enum class NetworkClass { Online, Degraded, Offline }
