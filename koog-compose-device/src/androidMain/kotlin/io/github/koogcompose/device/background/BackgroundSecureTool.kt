package io.github.koogcompose.device.background

import android.content.Context
import io.github.koogcompose.background.BackgroundJobProvider
import io.github.koogcompose.event.KoogEventBus
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import io.github.koogcompose.tool.PermissionLevel
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Abstract base for AI tools that delegate heavy work to a background job.
 *
 * Implement this class to create tools that run via WorkManager (Android) or
 * background sessions (iOS). The LLM sees this as a normal tool, but execution
 * is offloaded to a background worker.
 *
 * @param context     Application context — used for permission checks and WorkManager.
 * @param jobProvider The [BackgroundJobProvider] to enqueue work through.
 * @param eventBus    Shared bus — passed to [WorkManagerObserver] for status events.
 */
public abstract class BackgroundSecureTool(
    private val context: Context,
    private val jobProvider: BackgroundJobProvider,
    private val eventBus: KoogEventBus,
) : SecureTool {

    /** Unique tool name exposed to the LLM ( SecureTool.name ). */
    public abstract override val name: String

    /** Android permission strings that must be granted before the job is enqueued. */
    public open val requiredPermissions: List<String> = emptyList()

    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE

    /**
     * Builds the [Map] of key-value pairs passed into the background worker.
     */
    public abstract fun buildInputData(args: Map<String, String>): Map<String, String>

    /**
     * Entry point called by the tool dispatch layer. Enqueues a background job
     * and returns immediately. The LLM sees a success message with the job ID.
     */
    final override suspend fun execute(args: JsonObject): ToolResult {
        val missing = requiredPermissions.filter { permission ->
            context.checkSelfPermission(permission) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            return ToolResult.Failure(
                "Cannot run $name: missing permissions: ${missing.joinToString()}"
            )
        }

        val jobId = "$name-${UUID.randomUUID()}"
        val inputData = buildInputData(args.mapValues { (_, v) -> v.toString() })

        jobProvider.enqueue(jobId, inputData)

        WorkManagerObserver(context, jobId, eventBus).attach()

        return ToolResult.Success("Job [$jobId] started in background.")
    }
}
