package com.caydey.ffshare.autocompress

import android.app.job.JobParameters
import android.app.job.JobService
import androidx.work.Data
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.security.MessageDigest

/**
 * Purpose: handles MediaStore triggers, scans selected local trees, and queues unique work.
 * Invocation: AutoCompressScheduler's TriggerContentUri JobScheduler job.
 * Contract: the interruptible thread performs one enumeration and atomic DB claims before enqueue;
 * successful discovery re-registers before jobFinished exactly like AOSP PhotosContentJob, while a
 * discovery failure asks JobScheduler to retry the current run.
 * Verification: source ordering readback only; JobScheduler/WorkManager runtime is UNVERIFIED.
 */
class MediaChangeJobService : JobService() {
    @Volatile private var discoveryThread: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val thread = Thread {
            var retryJob = false
            try {
                discoverAndQueue()
            } catch (error: Throwable) {
                retryJob = true
                AutoCompressPrefs.setStatus(this, "Discovery failed: ${error.javaClass.simpleName}")
            } finally {
                val stillOwnsRun = synchronized(this) {
                    if (discoveryThread === Thread.currentThread()) {
                        discoveryThread = null
                        true
                    } else {
                        false
                    }
                }
                if (stillOwnsRun) {
                    // AOSP's PhotosContentJob re-registers before jobFinished. If discovery failed,
                    // ask JobScheduler to retry this run instead of replacing it with a fresh watcher.
                    if (!retryJob) retryJob = !AutoCompressScheduler.schedule(this)
                    jobFinished(params, retryJob)
                }
            }
        }
        synchronized(this) { discoveryThread = thread }
        thread.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        synchronized(this) {
            discoveryThread?.interrupt()
            discoveryThread = null
        }
        return true
    }

    private fun discoverAndQueue() {
        if (!AutoCompressPrefs.isEnabled(this)) return

        val db = SeenDb(this)
        val wm = WorkManager.getInstance(this)

        for (folder in AutoCompressPrefs.folders(this)) {
            val items = FolderScanner.scan(this, folder).toList()
            db.reconcileFolder(folder.toString(), items.mapTo(mutableSetOf()) { it.name })
            for (item in items) {
                if (Thread.currentThread().isInterrupted) return
                if (!db.claim(item)) continue

                val data = Data.Builder()
                    .putString(AutoCompressWorker.KEY_URI, item.uri.toString())
                    .putString(AutoCompressWorker.KEY_FOLDER_URI, folder.toString())
                    .putString(AutoCompressWorker.KEY_NAME, item.name)
                    .putString(AutoCompressWorker.KEY_MIME, item.mimeType)
                    .putLong(AutoCompressWorker.KEY_SIZE, item.size)
                    .putLong(AutoCompressWorker.KEY_MODIFIED, item.modified)
                    .build()

                val request = OneTimeWorkRequestBuilder<AutoCompressWorker>()
                    .setInputData(data)
                    .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                    .addTag(AutoCompressWorker.TAG_ALL)
                    .addTag(AutoCompressWorker.folderTag(folder.toString()))
                    .build()

                runCatching {
                    wm.enqueueUniqueWork(
                        "ffshare-auto-${sha256("${folder}\u0000${item.name}")}",
                        // A changed generation supersedes in-flight work for the same folder/name.
                        // Snapshot-conditional DB updates prevent the cancelled generation from
                        // overwriting the replacement request's state.
                        ExistingWorkPolicy.REPLACE,
                        request
                    ).result.get()
                }.onFailure {
                    db.markState(
                        item,
                        "FAILED",
                        "WorkManager enqueue failed",
                        incrementAttempt = true
                    )
                    throw it
                }
            }
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
