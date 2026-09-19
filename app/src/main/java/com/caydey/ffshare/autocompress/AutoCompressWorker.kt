package com.caydey.ffshare.autocompress

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileInputStream

/**
 * Purpose: waits for a stable media file, compresses it in app cache, and safely replaces its SAF
 * source while preserving the original name and folder.
 * Invocation: MediaChangeJobService queues one unique request per unseen document URI.
 * Contract: source is never deleted before a copied, size-verified replacement exists; a failed
 * rename leaves the staged compressed bytes recoverable. Foreground notification covers long work.
 * Verification: replacement ordering is source-reviewed; WorkManager/SAF runtime is UNVERIFIED.
 */
class AutoCompressWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sourceUri = Uri.parse(inputData.getString(KEY_URI) ?: return Result.failure())
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val mime = inputData.getString(KEY_MIME) ?: "application/octet-stream"

        setForeground(createForegroundInfo(name))

        val source = DocumentFile.fromSingleUri(applicationContext, sourceUri)
            ?: return Result.failure()

        if (!source.exists() || !source.isFile) return Result.failure()

        // Do not encode a camera/video file that is still growing.
        val firstSize = source.length()
        val firstModified = source.lastModified()
        delay(3_000L)
        val secondSize = source.length()
        val secondModified = source.lastModified()
        if (firstSize != secondSize || firstModified != secondModified) {
            return Result.retry()
        }

        val suffix = name.substringAfterLast('.', "tmp").let { ".$it" }
        val temp = File.createTempFile("ffshare-auto-", suffix, applicationContext.cacheDir)

        try {
            val ok = CompressionBridge.compressWithExistingFfshareSettings(
                applicationContext,
                sourceUri,
                temp,
                name,
                mime
            )
            if (!ok || !temp.exists() || temp.length() <= 0L) return Result.failure()

            if (!replaceOriginalSafely(sourceUri, name, mime, temp)) return Result.failure()
            return Result.success()
        } finally {
            temp.delete()
        }
    }

    /**
     * Safe replacement strategy:
     * 1. Create a second file in the SAME watched folder with a temporary name.
     * 2. Copy the completed FFmpeg output into it and verify byte count.
     * 3. Delete the original only after the staged compressed file is complete.
     * 4. Rename the staged file to the exact original filename.
     *
     * If step 4 fails, the compressed bytes are still present under the temporary name,
     * so a rename failure cannot silently destroy both copies.
     */
    private fun replaceOriginalSafely(
        sourceUri: Uri,
        originalName: String,
        mimeType: String,
        compressed: File
    ): Boolean {
        val resolver = applicationContext.contentResolver
        val parentUri = Uri.parse(inputData.getString(KEY_FOLDER_URI) ?: return false)
        val parent = DocumentFile.fromTreeUri(applicationContext, parentUri) ?: return false

        val extension = originalName.substringAfterLast('.', "")
        val base = if (extension.isEmpty()) originalName else originalName.dropLast(extension.length + 1)
        val stageName = if (extension.isEmpty()) {
            ".${base}.ffshare_pending"
        } else {
            ".${base}.ffshare_pending.$extension"
        }

        val staged = parent.createFile(mimeType, stageName) ?: return false

        try {
            resolver.openOutputStream(staged.uri, "w")?.use { out ->
                FileInputStream(compressed).use { input -> input.copyTo(out) }
            } ?: return false

            val stagedDoc = DocumentFile.fromSingleUri(applicationContext, staged.uri) ?: return false
            if (stagedDoc.length() != compressed.length()) return false

            // Delete original only after the replacement is fully present and verified.
            val deleted = resolver.delete(sourceUri, null, null) > 0
            if (!deleted) return false

            // SAF rename keeps the file in the same directory.
            if (!stagedDoc.renameTo(originalName)) {
                // Recovery-safe failure: compressed data still exists with stageName.
                SeenDb(applicationContext).markSeen(
                    stagedDoc.uri.toString(),
                    parentUri.toString(),
                    stagedDoc.length(),
                    stagedDoc.lastModified(),
                    "OUTPUT_PENDING_RENAME"
                )
                return false
            }

            // Record the replacement URI because the create/rename itself can cause another
            // MediaStore notification. This prevents a compression loop.
            SeenDb(applicationContext).markSeen(
                stagedDoc.uri.toString(),
                parentUri.toString(),
                stagedDoc.length(),
                stagedDoc.lastModified(),
                "OUTPUT"
            )
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun createForegroundInfo(name: String): ForegroundInfo {
        val channelId = "ffshare_auto_compress"
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Automatic compression", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("FFShare automatic compression")
            .setContentText(name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_NAME = "name"
        const val KEY_MIME = "mime"
        const val KEY_SIZE = "size"
        const val KEY_MODIFIED = "modified"
        private const val NOTIFICATION_ID = 0x4653
    }
}
