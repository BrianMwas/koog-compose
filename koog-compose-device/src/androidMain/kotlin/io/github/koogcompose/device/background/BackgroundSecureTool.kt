package io.github.koogcompose.device.background

import android.content.Context
import io.github.koogcompose.background.BackgroundJobProvider
import io.github.koogcompose.event.KoogEventBus
import java.util.UUID

/**
 * Abstract base for AI tools that delegate heavy work to a background job.
 *
 * @param context     Application context — used for permission checks and WorkManager.
 * @param jobProvider The [BackgroundJobProvider] to enqueue work through.
 * @param eventBus    Shared bus — passed to [WorkManagerObserver] for status events.
 */
public abstract class BackgroundSecureTool(
    private val context: Context,
    private val jobProvider: BackgroundJobProvider,
    private val eventBus: KoogEventBus,
) {
    /** Unique tool name exposed to the LLM. */
    public abstract val toolName: String

    /**
     * Android permission strings that must be granted before the job is enqueued.
     */
    public open val requiredPermissions: List<String> = emptyList()

    /**
     * Builds the [Map] of key-value pairs passed into the background worker.
     */
    public abstract fun buildInputData(args: Map<String, String>): Map<String, String>

    /**
     * Entry point called by the tool dispatch layer.
     */
    public fun execute(args: Map<String, String>): ToolResult {
        val missing = requiredPermissions.filter { permission ->
            context.checkSelfPermission(permission) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            return ToolResult.Error(
                "Cannot run $toolName: missing permissions: ${missing.joinToString()}"
            )
        }

        val jobId = "$toolName-${UUID.randomUUID()}"
        val inputData = buildInputData(args)

        jobProvider.enqueue(jobId, inputData)

        WorkManagerObserver(context, jobId, eventBus).attach()

        return ToolResult.Success("Job [$jobId] started in background.")
    }
}

public sealed class ToolResult {
    public data class Success(val message: String) : ToolResult()
    public data class Error(val message: String) : ToolResult()
}
