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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Bridges WorkManager's [WorkInfo] lifecycle to [KoogEventBus].
 *
 * Attach one observer per job after calling [BackgroundJobProvider.enqueue].
 * The observer removes itself automatically once the job reaches a terminal
 * state (SUCCEEDED, FAILED, CANCELLED) so there are no leaks.
 *
 * The internal [CoroutineScope] is cancelled on [detach] or when the job
 * reaches a terminal state — preventing scope leaks.
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
                eventBus.dispatch(
                    KoogEvent.BackgroundJobStatus(
                        timestampMs = Clock.System.now().toEpochMilliseconds(),
                        phaseName = null,
                        jobId = jobId,
                        status = status
                    )
                )
            }

            if (info.state.isFinished) {
                liveData.removeObserver(observer!!)
                observer = null
                scope.cancel()
            }
        }.also { liveData.observeForever(it) }
    }

    /**
     * Stops observing immediately and cancels the internal scope.
     * Call this when the owning component is destroyed to prevent leaks.
     */
    public fun detach() {
        observer?.let {
            workManager.getWorkInfosForUniqueWorkLiveData(jobId).removeObserver(it)
            observer = null
        }
        scope.cancel()
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
