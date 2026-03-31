package io.github.koogcompose.device.background



import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Base class for all AI-triggered background tasks in koog-compose.
 *
 * Subclass this and override [doWork] to implement your task logic.
 * The [jobId] is automatically extracted from input data so subclasses
 * never need to parse it manually.
 *
 * ### Example
 * ```kotlin
 * class TranscriptionWorker(ctx: Context, params: WorkerParameters) :
 *     KoogCoroutineWorker(ctx, params) {
 *
 *     override suspend fun doWork(): Result {
 *         val audioUri = inputData.getString("audio_uri") ?: return Result.failure()
 *         transcribe(audioUri)
 *         return Result.success()
 *     }
 * }
 * ```
 *
 * Status events ([KoogEvent.BackgroundJobStatus]) are dispatched by
 * [WorkManagerObserver], not from inside [doWork], so this class stays
 * focused purely on the work itself.
 */
abstract class KoogCoroutineWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /** The stable job identifier supplied at [BackgroundJobProvider.enqueue]. */
    protected val jobId: String
        get() = inputData.getString(KEY_JOB_ID)
            ?: error("KoogCoroutineWorker requires KEY_JOB_ID in inputData")

    companion object {
        const val KEY_JOB_ID = "koog_job_id"
    }
}