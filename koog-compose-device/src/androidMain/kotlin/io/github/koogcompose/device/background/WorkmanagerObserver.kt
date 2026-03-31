package io.github.koogcompose.device.background

import android.content.Context
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.koogcompose.event.KoogEvent
import io.github.koogcompose.event.KoogEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bridges WorkManager's [WorkInfo] lifecycle to [KoogEventBus].
 *
 * Attach one observer per job after calling [BackgroundJobProvider.enqueue].
 * The observer removes itself automatically once the job reaches a terminal
 * state (SUCCEEDED, FAILED, CANCELLED) so there are no leaks.
 *
 * @param context   Application context for WorkManager access.
 * @param jobId     Must match the unique work name used in [WorkManagerJobProvider.enqueue].
 * @param eventBus  The shared [KoogEventBus] instance from your [KoogComposeContext].
 */
public class WorkManagerObserver(
    private val context: Context,
    private val jobId: String,
    private val eventBus: KoogEventBus,
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workManager: WorkManager = WorkManager.getInstance(context)

    // Retained so we can remove it on terminal state.
    private var observer: Observer<List<WorkInfo>>? = null

    /**
     * Starts observing the unique work named [jobId].
     */
    public fun attach() {
        if (observer != null) return

        val liveData = workManager.getWorkInfosForUniqueWorkLiveData(jobId)

        observer = Observer { workInfoList ->
            val info = workInfoList.firstOrNull() ?: return@Observer
            val status = info.state.toStatusString()

            scope.launch {
                // Using internal dispatch since it's within the same package/module scope
                // or we need to ensure the bus allows external dispatch for background jobs.
                eventBus.dispatch(
                    KoogEvent.BackgroundJobStatus(
                        timestampMs = System.currentTimeMillis(),
                        phaseName = null,
                        jobId = jobId,
                        status = status
                    )
                )
            }

            if (info.state.isFinished) {
                liveData.removeObserver(observer!!)
                observer = null
            }
        }.also { liveData.observeForever(it) }
    }

    /** Stops observing immediately, regardless of job state. */
    public fun detach() {
        observer?.let {
            workManager
                .getWorkInfosForUniqueWorkLiveData(jobId)
                .removeObserver(it)
            observer = null
        }
    }

    private fun WorkInfo.State.toStatusString(): String = when (this) {
        WorkInfo.State.ENQUEUED -> KoogEvent.BackgroundJobStatus.SCHEDULED
        WorkInfo.State.BLOCKED -> KoogEvent.BackgroundJobStatus.SCHEDULED
        WorkInfo.State.RUNNING -> KoogEvent.BackgroundJobStatus.RUNNING
        WorkInfo.State.SUCCEEDED -> KoogEvent.BackgroundJobStatus.COMPLETED
        WorkInfo.State.FAILED -> KoogEvent.BackgroundJobStatus.FAILED
        WorkInfo.State.CANCELLED -> KoogEvent.BackgroundJobStatus.FAILED
    }
}
