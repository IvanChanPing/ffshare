package com.caydey.ffshare.autocompress

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Purpose: adapts automatic folder work to FFShare's existing compressor and saved settings.
 * Invocation: AutoCompressWorker calls this after the source has been stable for the settle delay.
 * Contract: no duplicate preferences or share sheet; originalName controls the output container,
 * and success is returned only from the shared FFmpegKit path.
 * Verification: bridge is source-wired to MediaCompressor; compilation/device execution UNVERIFIED.
 */
object CompressionBridge {
    suspend fun compressWithExistingFfshareSettings(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        originalName: String,
        mimeType: String
    ): Boolean {
        return com.caydey.ffshare.utils.MediaCompressor(context).compressToFile(
            inputFileUri = inputUri,
            outputFile = outputFile,
            originalName = originalName
        )
    }
}
