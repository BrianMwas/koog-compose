package io.github.koogcompose.device.background

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.github.koogcompose.background.BackgroundJobProvider
import java.util.concurrent.TimeUnit

/**
 * WorkManager-backed [BackgroundJobProvider]. Enqueues [workerClass] as unique
 * work named after the job ID, so the job survives the app being backgrounded
 * or killed — WorkManager persists the request and runs it independently of
 * the app process.
 *
 * One instance is bound to a single [KoogCoroutineWorker] subclass; a tool
 * that needs different background work should hold its own provider pointed
 * at its own worker class.
 *
 * ```kotlin
 * class MyTool(context: Context, eventBus: KoogEventBus) : BackgroundSecureTool(
 *     context = context,
 *     jobProvider = WorkManagerJobProvider(context, MyWorker::class.java),
 *     eventBus = eventBus,
 * ) { ... }
 * ```
 */
public class WorkManagerJobProvider(
    private val context: Context,
    private val workerClass: Class<out KoogCoroutineWorker>,
) : BackgroundJobProvider {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    override fun enqueue(jobId: String, data: Map<String, String>) {
        val inputData = Data.Builder().apply {
            data.forEach { (key, value) -> putString(key, value) }
            putString(KoogCoroutineWorker.KEY_JOB_ID, jobId)
        }.build()

        val delaySeconds = data[DELAY_SECONDS_KEY]?.toLongOrNull() ?: 0L

        val request = OneTimeWorkRequest.Builder(workerClass)
            .setInputData(inputData)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(jobId, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(jobId: String) {
        workManager.cancelUniqueWork(jobId)
    }

    public companion object {
        /** Optional key in [enqueue]'s `data` map — delays the job by this many seconds. */
        public const val DELAY_SECONDS_KEY: String = "koog_delay_seconds"
    }
}
