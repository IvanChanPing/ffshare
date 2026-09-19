package com.caydey.ffshare.autocompress

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Purpose: enumerates only direct, supported media children of a selected SAF tree.
 * Invocation: baseline seeding and each MediaStore-triggered discovery pass.
 * Contract: top-level only, media MIME families only; it does not recurse or poll on its own.
 * Verification: source readback only; provider behavior is UNVERIFIED.
 */
object FolderScanner {
    fun scan(context: Context, folderUri: Uri): Sequence<MediaCandidate> = sequence {
        val root = DocumentFile.fromTreeUri(context, folderUri) ?: return@sequence
        for (file in root.listFiles()) {
            if (!file.isFile) continue
            val mime = file.type ?: continue
            if (!(mime.startsWith("video/") || mime.startsWith("image/") || mime.startsWith("audio/"))) continue
            val name = file.name ?: continue
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
}
