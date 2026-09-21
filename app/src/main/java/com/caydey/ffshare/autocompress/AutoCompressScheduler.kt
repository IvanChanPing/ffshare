package com.caydey.ffshare.autocompress

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Purpose: registers Android's event-driven MediaStore content watcher.
 * Invocation: settings changes, boot/package replacement, and each completed JobService callback.
 * Contract: the job is intentionally non-persisted and re-registered after callbacks; no timer scan.
 * Verification: source/API wiring readback only; OS callback delivery is UNVERIFIED.
 */
object AutoCompressScheduler {
    const val JOB_ID = 0x46534653 // "FSFS"

    fun schedule(context: Context): Boolean {
        val supportedFolders = AutoCompressPrefs.folders(context).filter(FolderScanner::isSupportedLocalTree)
        if (!AutoCompressPrefs.isEnabled(context) || supportedFolders.isEmpty()) {
            cancel(context)
            return true
        }

        val scheduler = context.getSystemService(JobScheduler::class.java)
        val builder = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, MediaChangeJobService::class.java)
        )
            // Mirror AOSP's PhotosContentJob pattern: monitor both the concrete external-files
            // collection and every descendant of the provider root because image, video, and audio
            // collection notifications are siblings of the files collection, not its descendants.
            .addTriggerContentUri(JobInfo.TriggerContentUri(
                MediaStore.Files.getContentUri("external"),
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
            ))
            .addTriggerContentUri(JobInfo.TriggerContentUri(
                Uri.parse("content://${MediaStore.AUTHORITY}/"),
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
            ))
            .setTriggerContentUpdateDelay(2_000L)
            .setTriggerContentMaxDelay(8_000L)

        return try {
            val scheduled = scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
            AutoCompressPrefs.setStatus(
                context,
                if (scheduled) "Watching selected folders" else "Android refused the folder watcher"
            )
            scheduled
        } catch (error: RuntimeException) {
            AutoCompressPrefs.setStatus(context, "Unable to watch folders: ${error.javaClass.simpleName}")
            false
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
