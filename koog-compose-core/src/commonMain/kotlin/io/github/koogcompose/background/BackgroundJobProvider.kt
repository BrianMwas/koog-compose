package io.github.koogcompose.background

/**
 * Platform-agnostic interface for enqueuing background tasks triggered by AI tools.
 */
public interface BackgroundJobProvider {
    /**
     * Enqueues a task identified by [jobId] with the provided [data].
     */
    public fun enqueue(jobId: String, data: Map<String, String>)

    /**
     * Cancels a previously enqueued task.
     */
    public fun cancel(jobId: String)
}
