package com.caydey.ffshare.autocompress

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore

/**
 * Purpose: registers Android's event-driven MediaStore TriggerContentUri watcher.
 * Invocation: settings changes, boot/package replacement, and each completed JobService callback.
 * Contract: the job is intentionally non-persisted and re-registered after callbacks; no timer scan.
 * Verification: source/API wiring readback only; OS callback delivery is UNVERIFIED.
 */
object AutoCompressScheduler {
    const val JOB_ID = 0x46534653 // "FSFS"

    fun schedule(context: Context) {
        if (!AutoCompressPrefs.isEnabled(context) || AutoCompressPrefs.folders(context).isEmpty()) {
            cancel(context)
            return
        }

        val scheduler = context.getSystemService(JobScheduler::class.java)
        val trigger = JobInfo.TriggerContentUri(
            MediaStore.Files.getContentUri("external"),
            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
        )

        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, MediaChangeJobService::class.java)
        )
            .addTriggerContentUri(trigger)
            .setTriggerContentUpdateDelay(2_000L)
            .setTriggerContentMaxDelay(8_000L)
            .build()

        scheduler.schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
