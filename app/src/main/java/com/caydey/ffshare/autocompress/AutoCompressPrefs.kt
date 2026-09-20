package com.caydey.ffshare.autocompress

import android.content.Context
import android.net.Uri

/**
 * Purpose: persists the enabled flag and SAF tree URIs selected by the user.
 * Invocation: AutoCompressSettingsActivity writes it; scheduler/receiver read it.
 * Contract: URI strings are the durable folder identity; no filesystem polling state is stored here.
 * Verification: source readback only; persistence across a device reboot is UNVERIFIED.
 */
object AutoCompressPrefs {
    private const val PREFS = "auto_compress"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FOLDERS = "folders"
    private const val KEY_STATUS = "status"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun folders(context: Context): Set<Uri> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_FOLDERS, emptySet())
            .orEmpty()
            .map(Uri::parse)
            .toSet()

    fun addFolder(context: Context, uri: Uri) {
        val current = folders(context).map(Uri::toString).toMutableSet()
        current += uri.toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_FOLDERS, current).apply()
    }

    fun removeFolder(context: Context, uri: Uri) {
        val current = folders(context).map(Uri::toString).toMutableSet()
        current -= uri.toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_FOLDERS, current).apply()
    }

    fun status(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "")
            .orEmpty()

    fun setStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATUS, status).apply()
    }
}
