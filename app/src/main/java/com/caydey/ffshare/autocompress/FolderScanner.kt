package com.caydey.ffshare.autocompress

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Purpose: enumerates only direct, supported media children of a selected SAF tree.
 * Invocation: baseline seeding and each MediaStore-triggered discovery pass.
 * Contract: top-level only, media MIME families only, pending replacements excluded; missing provider
 * MIME values fall back to known filename extensions. It does not recurse or poll on its own.
 * Verification: source readback only; provider behavior is UNVERIFIED.
 */
object FolderScanner {
    fun isSupportedLocalTree(uri: Uri): Boolean =
        DocumentsContract.isTreeUri(uri) && uri.authority == EXTERNAL_STORAGE_AUTHORITY

    fun scan(context: Context, folderUri: Uri): Sequence<MediaCandidate> = sequence {
        if (!isSupportedLocalTree(folderUri)) return@sequence
        val root = DocumentFile.fromTreeUri(context, folderUri) ?: return@sequence
        for (file in root.listFiles()) {
            if (Thread.currentThread().isInterrupted) return@sequence
            if (!file.isFile) continue
            val name = file.name ?: continue
            if (name.contains(PENDING_MARKER)) continue
            val mime = file.type ?: mimeFromName(name) ?: continue
            if (!(mime.startsWith("video/") || mime.startsWith("image/") || mime.startsWith("audio/"))) continue
            yield(
                MediaCandidate(
                    folderUri = folderUri,
                    uri = file.uri,
                    name = name,
                    mimeType = mime,
                    size = file.length(),
                    modified = file.lastModified()
                )
            )
        }
    }

    /** One directory enumeration is the authority for a name lookup; never issue repeated findFile IPCs. */
    fun find(context: Context, folderUri: Uri, name: String): MediaCandidate? =
        scan(context, folderUri).firstOrNull { it.name == name }

    private fun mimeFromName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4", "mov" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        else -> null
    }

    const val PENDING_MARKER = ".ffshare_pending_"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
}
