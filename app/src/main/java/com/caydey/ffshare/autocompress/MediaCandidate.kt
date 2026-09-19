package com.caydey.ffshare.autocompress

import android.net.Uri

/**
 * Purpose: immutable metadata passed from SAF discovery into seen-state and WorkManager input.
 * Invocation: produced by FolderScanner for each direct media child.
 * Contract: URI, folder, name, MIME, size, and modification time identify one candidate snapshot.
 * Verification: source readback only; values from a live provider are UNVERIFIED.
 */
data class MediaCandidate(
    val folderUri: Uri,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modified: Long
)
