package io.github.koogcompose.device.background


import android.content.Context
import io.github.koogcompose.event.KoogEventBus
import java.util.UUID

/**
 * Abstract base for AI tools that delegate heavy work to a background job.
 *
 * ### Contract
 * When the LLM calls [execute]:
 * 1. [requiredPermissions] are checked — if any are missing, a [ToolResult.Error]
 *    is returned immediately with a clear message so the LLM can inform the user.
 * 2. [buildInputData] is called to construct the job payload from the tool's arguments.
 * 3. The job is enqueued via [BackgroundJobProvider] and a [WorkManagerObserver]
 *    is attached so status events flow through [KoogEventBus].
 * 4. A [ToolResult.Success] is returned *immediately* — the LLM does not block
 *    waiting for the job to finish.
 *
 * ### Example
 * ```kotlin
 * class TranscribeAudioTool(
 *     context: Context,
 *     jobProvider: BackgroundJobProvider,
 *     eventBus: KoogEventBus,
 * ) : BackgroundSecureTool(context, jobProvider, eventBus) {
 *
 *     override val toolName = "transcribe_audio"
 *     override val requiredPermissions = listOf(Manifest.permission.RECORD_AUDIO)
 *
 *     override fun buildInputData(args: Map<String, String>) =
 *         mapOf("audio_uri" to (args["uri"] ?: error("uri required")))
 * }
 * ```
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
     * Return an empty list if no permissions are required.
     */
    public open val requiredPermissions: List<String> = emptyList()

    /**
     * Builds the [Map] of key-value pairs passed into the [KoogCoroutineWorker]
     * as input data.
     *
     * @param args The raw argument map supplied by the LLM tool call.
     */
    public abstract fun buildInputData(args: Map<String, String>): Map<String, String>

    /**
     * Entry point called by the tool dispatch layer.
     *
     * Returns [ToolResult.Success] immediately after enqueueing — never suspends
     * waiting for the background job to finish.
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

/**
 * Minimal result type for [BackgroundSecureTool].
 * Map to your existing ToolResult type if one already exists in the project.
 */
public sealed class ToolResult {
    public data class Success(val message: String) : ToolResult()
    public data class Error(val message: String) : ToolResult()
}