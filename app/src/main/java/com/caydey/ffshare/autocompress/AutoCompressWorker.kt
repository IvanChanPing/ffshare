package com.caydey.ffshare.autocompress

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.caydey.ffshare.extensions.mediaCacheDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Purpose: compresses one stable local SAF document and transactionally replaces it in place.
 * Invocation: MediaChangeJobService queues a uniquely named WorkManager request after an atomic DB claim.
 * Contract: folder/name is re-resolved before every critical phase; the source is renamed to a backup,
 * the compressed stage is renamed to the exact original name and hash-verified, and only then is the
 * backup deleted. Any pre-verification failure restores the backup or records a visible recovery state.
 * Verification: replacement ordering and every exit path are source-checked; Android provider and device
 * execution remain unverified until an authorized build and physical local-storage test.
 */
class AutoCompressWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val db by lazy { SeenDb(applicationContext) }

    override suspend fun doWork(): Result {
        val folderUri = inputData.getString(KEY_FOLDER_URI)?.let(Uri::parse) ?: return Result.failure()
        val originalName = inputData.getString(KEY_NAME) ?: return Result.failure()
        val sourceHint = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        val expected = MediaCandidate(
            folderUri = folderUri,
            uri = sourceHint,
            name = originalName,
            mimeType = inputData.getString(KEY_MIME) ?: "application/octet-stream",
            size = inputData.getLong(KEY_SIZE, -1L),
            modified = inputData.getLong(KEY_MODIFIED, -1L)
        )

        if (!folderIsActive(folderUri)) return cancelled(expected, "Folder is no longer enabled")
        if (expected.size <= 0L) return failed(expected, "Source size is unavailable", terminal = true)
        if (!db.tryStart(expected)) return Result.success()

        val tempDir = File(applicationContext.mediaCacheDir, "autocompress").apply { mkdirs() }
        if (!tempDir.isDirectory || tempDir.usableSpace < expected.size + MIN_FREE_SPACE_BYTES) {
            return failed(expected, "Not enough cache space")
        }
        val suffix = originalName.substringAfterLast('.', "tmp").let { ".$it" }
        val compressed = runCatching {
            File.createTempFile("ffshare-auto-", suffix, tempDir)
        }.getOrElse {
            return failed(expected, "Unable to create cache output")
        }

        return try {
            processingMutex.withLock { process(expected, compressed) }
        } catch (cancelled: CancellationException) {
            db.markState(
                expected,
                "FAILED",
                "Worker cancelled",
                incrementAttempt = true
            )
            throw cancelled
        } catch (error: Throwable) {
            failed(expected, error.javaClass.simpleName)
        } finally {
            compressed.delete()
        }
    }

    /**
     * Purpose: runs the complete automatic-compression transaction under one process-wide lock.
     * Invocation: doWork enters this only after the exact queued snapshot wins SeenDb.tryStart.
     * Contract: no second worker can settle, encode, or promote while this generation is active;
     * all business failures are recorded in SeenDb before a WorkManager result is returned.
     * Verification: lock scope and return paths are source-checked; runtime cancellation is unverified.
     */
    private suspend fun process(expected: MediaCandidate, compressed: File): Result {
        setForeground(createForegroundInfo(expected.name))
        val stable = awaitStableSource(expected)
            ?: return failed(expected, "Source changed while being written")
        if (!folderIsActive(expected.folderUri)) return cancelled(expected, "Folder was disabled")

        val compressedOk = withTimeout(MAX_COMPRESSION_MS) {
            CompressionBridge.compressWithExistingFfshareSettings(
                applicationContext,
                stable.uri,
                compressed,
                expected.name
            )
        }
        if (!compressedOk || compressed.length() <= 0L) {
            return failed(expected, "Compression failed")
        }
        if (compressed.length() >= stable.size) {
            return failed(expected, "Compressed output was not smaller", terminal = true)
        }

        return when (replaceWithBackup(stable, compressed)) {
            ReplaceResult.SUCCESS -> {
                AutoCompressPrefs.setStatus(applicationContext, "Compressed ${expected.name}")
                Result.success()
            }
            ReplaceResult.RECOVERY_NEEDED -> {
                AutoCompressPrefs.setStatus(
                    applicationContext,
                    "Replacement for ${expected.name} needs recovery; original data was retained"
                )
                Result.failure()
            }
            ReplaceResult.RETRY -> failed(expected, "Source or storage provider changed")
        }
    }

    /**
     * Purpose: owns the sole irreversible file transition.
     * Invocation: doWork calls it after compression and source settling complete.
     * Contract: no original bytes are deleted until the final compressed file has the exact name, size,
     * and SHA-256 digest. A failed stage promotion is rolled back from the renamed original backup.
     * Verification: source-path audit only; provider rename/delete behavior is device-unverified.
     */
    private fun replaceWithBackup(expected: MediaCandidate, compressed: File): ReplaceResult {
        val resolver = applicationContext.contentResolver
        val parent = DocumentFile.fromTreeUri(applicationContext, expected.folderUri)
            ?: return ReplaceResult.RETRY
        if (!folderIsActive(expected.folderUri) || !parent.canWrite()) return ReplaceResult.RETRY

        val source = FolderScanner.find(applicationContext, expected.folderUri, expected.name)
            ?: return ReplaceResult.RETRY
        if (!source.sameSnapshot(expected) ||
            !supports(source.uri, DocumentsContract.Document.FLAG_SUPPORTS_RENAME) ||
            !supports(source.uri, DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
        ) {
            return ReplaceResult.RETRY
        }

        val stageName = pendingName(expected.name, "stage")
        val staged = parent.createFile(expected.mimeType, stageName) ?: return ReplaceResult.RETRY
        var stageUri: Uri? = staged.uri
        var backupUri: Uri? = null
        var finalUri: Uri? = null
        var finalVerified = false

        try {
            resolver.openOutputStream(staged.uri, "wt")?.use { output ->
                compressed.inputStream().use { input -> input.copyTo(output) }
                output.flush()
            } ?: return ReplaceResult.RETRY

            if (!supports(staged.uri, DocumentsContract.Document.FLAG_SUPPORTS_RENAME) ||
                !supports(staged.uri, DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
            ) {
                return ReplaceResult.RETRY
            }

            val expectedOutput = fingerprint(compressed)
            if (fingerprint(staged.uri) != expectedOutput) return ReplaceResult.RETRY

            // A second full directory enumeration is intentionally separated from the first by the
            // complete stage write and hash. It is the final source-stability confirmation.
            val confirmed = FolderScanner.find(applicationContext, expected.folderUri, expected.name)
                ?: return ReplaceResult.RETRY
            if (!folderIsActive(expected.folderUri) || !confirmed.sameSnapshot(expected)) {
                return ReplaceResult.RETRY
            }

            val backupName = pendingName(expected.name, "backup")
            backupUri = safeRename(confirmed.uri, backupName) ?: return ReplaceResult.RETRY

            finalUri = safeRename(staged.uri, expected.name)
            if (finalUri == null) {
                val retainedBackup = backupUri
                    ?: return recovery(expected, "Original backup URI was lost", staged.uri)
                val restored = restoreOriginal(retainedBackup, null, expected.folderUri, expected.name)
                backupUri = if (restored) null else retainedBackup
                return if (restored) ReplaceResult.RETRY else recovery(expected, "Stage promotion failed", backupUri)
            }
            stageUri = null

            val finalCandidate = documentAt(expected.folderUri, finalUri)
            finalVerified = finalCandidate?.name == expected.name &&
                fingerprint(finalUri) == expectedOutput
            if (!finalVerified) {
                val retainedBackup = backupUri
                    ?: return recovery(expected, "Original backup URI was lost", finalUri)
                val restored = restoreOriginal(retainedBackup, finalUri, expected.folderUri, expected.name)
                backupUri = if (restored) null else retainedBackup
                finalUri = null
                return if (restored) ReplaceResult.RETRY else recovery(expected, "Final verification failed", backupUri)
            }

            // This is the only deletion of original bytes, and it occurs after final verification.
            if (!safeDelete(backupUri)) {
                return recovery(expected, "Verified replacement kept an undeleted backup", backupUri)
            }
            backupUri = null

            db.markSeen(finalCandidate!!, "OUTPUT")
            return ReplaceResult.SUCCESS
        } catch (_: Throwable) {
            if (backupUri == null) return ReplaceResult.RETRY
            if (!finalVerified) {
                val retainedBackup = backupUri
                val restored = restoreOriginal(retainedBackup, finalUri, expected.folderUri, expected.name)
                backupUri = if (restored) null else retainedBackup
                if (restored) return ReplaceResult.RETRY
            }
            return recovery(expected, "Provider operation failed", backupUri ?: finalUri ?: stageUri)
        } finally {
            if (backupUri == null && stageUri != null) safeDelete(stageUri)
        }
    }

    private suspend fun awaitStableSource(queued: MediaCandidate): MediaCandidate? {
        var previous = FolderScanner.find(applicationContext, queued.folderUri, queued.name) ?: return null
        if (!previous.sameSnapshot(queued)) return null
        repeat(STABILITY_CONFIRMATIONS) {
            delay(STABILITY_INTERVAL_MS)
            val current = FolderScanner.find(applicationContext, queued.folderUri, queued.name) ?: return null
            if (!current.sameSnapshot(previous)) return null
            previous = current
        }
        return previous
    }

    private fun documentAt(folderUri: Uri, uri: Uri): MediaCandidate? = runCatching {
        val file = DocumentFile.fromSingleUri(applicationContext, uri) ?: return@runCatching null
        val name = file.name ?: return@runCatching null
        val mime = file.type ?: return@runCatching null
        MediaCandidate(folderUri, uri, name, mime, file.length(), file.lastModified())
    }.getOrNull()

    private fun restoreOriginal(backup: Uri, promoted: Uri?, folderUri: Uri, originalName: String): Boolean {
        if (promoted != null && !safeDelete(promoted)) return false
        val restored = safeRename(backup, originalName) ?: return false
        return documentAt(folderUri, restored)?.name == originalName
    }

    private fun recovery(expected: MediaCandidate, detail: String, retained: Uri?): ReplaceResult {
        val current = FolderScanner.find(applicationContext, expected.folderUri, expected.name)
        val recoveryItem = current ?: expected
        if (current != null) db.markSeen(current, "RECOVERY_NEEDED")
        db.markState(
            recoveryItem,
            "RECOVERY_NEEDED",
            "$detail${retained?.let { ": $it" }.orEmpty()}"
        )
        return ReplaceResult.RECOVERY_NEEDED
    }

    private fun pendingName(originalName: String, role: String): String =
        ".$originalName${FolderScanner.PENDING_MARKER}${role}_${UUID.randomUUID()}"

    private fun safeRename(uri: Uri, name: String): Uri? = runCatching {
        DocumentsContract.renameDocument(applicationContext.contentResolver, uri, name)
    }.getOrNull()

    private fun safeDelete(uri: Uri?): Boolean = uri == null || runCatching {
        DocumentsContract.deleteDocument(applicationContext.contentResolver, uri)
    }.getOrDefault(false)

    private fun supports(uri: Uri, flag: Int): Boolean = runCatching {
        applicationContext.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null
        )?.use { cursor -> cursor.moveToFirst() && cursor.getInt(0) and flag != 0 } == true
    }.getOrDefault(false)

    private fun fingerprint(file: File): Fingerprint = file.inputStream().use(::fingerprint)

    private fun fingerprint(uri: Uri): Fingerprint? = applicationContext.contentResolver
        .openInputStream(uri)?.use(::fingerprint)

    private fun fingerprint(input: InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            size += read
        }
        return Fingerprint(size, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun folderIsActive(folderUri: Uri): Boolean =
        FolderScanner.isSupportedLocalTree(folderUri) &&
            AutoCompressPrefs.isEnabled(applicationContext) &&
            folderUri in AutoCompressPrefs.folders(applicationContext)

    private fun cancelled(item: MediaCandidate, detail: String): Result {
        db.markState(item, "CANCELLED", detail)
        return Result.success()
    }

    private fun failed(item: MediaCandidate, detail: String, terminal: Boolean = false): Result {
        db.markState(
            item,
            if (terminal) "SKIPPED" else "FAILED",
            detail,
            incrementAttempt = !terminal
        )
        AutoCompressPrefs.setStatus(applicationContext, detail)
        return if (!terminal && runAttemptCount + 1 < SeenDb.MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private fun createForegroundInfo(name: String): ForegroundInfo {
        val channelId = "ffshare_auto_compress"
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Automatic compression", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("FFShare automatic compression")
            .setContentText(name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancel)
            .build()
        val notificationId = NOTIFICATION_BASE + (id.hashCode() and 0x0fff)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private data class Fingerprint(val size: Long, val sha256: String)
    private enum class ReplaceResult { SUCCESS, RETRY, RECOVERY_NEEDED }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_NAME = "name"
        const val KEY_MIME = "mime"
        const val KEY_SIZE = "size"
        const val KEY_MODIFIED = "modified"
        const val TAG_ALL = "ffshare-auto"
        private const val NOTIFICATION_BASE = 0x465300
        private const val STABILITY_CONFIRMATIONS = 3
        private const val STABILITY_INTERVAL_MS = 2_000L
        private const val MAX_COMPRESSION_MS = 6L * 60L * 60L * 1_000L
        private const val MIN_FREE_SPACE_BYTES = 50L * 1024L * 1024L
        private val processingMutex = Mutex()

        fun folderTag(folderUri: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(folderUri.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return "ffshare-folder-$hash"
        }
    }
}
