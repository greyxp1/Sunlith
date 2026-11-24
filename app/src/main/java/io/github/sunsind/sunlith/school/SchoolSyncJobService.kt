package io.github.sunsind.sunlith.school

import android.app.job.JobParameters
import android.app.job.JobService
import io.github.sunsind.sunlith.school.sync.SchoolScheduleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val SCHOOL_JOB_RETRY_DELAY = 45_000L

class SchoolSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        task = scope.launch { try { SchoolScheduleManager.get(this@SchoolSyncJobService).refreshForJob() } finally { if (isActive) jobFinished(params, false) } }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(); task = null; return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
