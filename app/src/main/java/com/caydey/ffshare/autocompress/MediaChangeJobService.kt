package com.caydey.ffshare.autocompress

import android.app.job.JobParameters
import android.app.job.JobService
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Purpose: handles a MediaStore content trigger, scans selected folders once, and queues unique work.
 * Invocation: AutoCompressScheduler's TriggerContentUri JobScheduler job.
 * Contract: mark-before-enqueue deduplicates duplicate notifications; the next watcher is registered
 * after discovery because content-trigger jobs cannot be persisted or periodic.
 * Verification: source ordering readback only; JobScheduler/WorkManager runtime is UNVERIFIED.
 */
class MediaChangeJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                discoverAndQueue()
            } finally {
                // TriggerContentUri jobs cannot be persisted or periodic. Android's documented
                // pattern is to schedule the next watcher after handling this callback.
                AutoCompressScheduler.schedule(this)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    private fun discoverAndQueue() {
        if (!AutoCompressPrefs.isEnabled(this)) return

        val db = SeenDb(this)
        val wm = WorkManager.getInstance(this)

        for (folder in AutoCompressPrefs.folders(this)) {
            for (item in FolderScanner.scan(this, folder)) {
                val key = item.uri.toString()
                if (db.contains(key)) continue

                // Mark before queueing so duplicate MediaStore notifications do not enqueue twice.
                db.markSeen(key, folder.toString(), item.size, item.modified, "QUEUED")

                val data = Data.Builder()
                    .putString(AutoCompressWorker.KEY_URI, key)
                    .putString(AutoCompressWorker.KEY_FOLDER_URI, folder.toString())
                    .putString(AutoCompressWorker.KEY_NAME, item.name)
                    .putString(AutoCompressWorker.KEY_MIME, item.mimeType)
                    .putLong(AutoCompressWorker.KEY_SIZE, item.size)
                    .putLong(AutoCompressWorker.KEY_MODIFIED, item.modified)
                    .build()

                val request = OneTimeWorkRequestBuilder<AutoCompressWorker>()
                    .setInputData(data)
                    .build()

                wm.enqueueUniqueWork(
                    "ffshare-auto-${key.hashCode()}",
                    ExistingWorkPolicy.KEEP,
                    request
                )
            }
        }
    }
}
