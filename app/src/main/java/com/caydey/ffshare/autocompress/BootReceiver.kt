package com.caydey.ffshare.autocompress

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Purpose: restores the content-trigger watcher after reboot or package replacement.
 * Invocation: BOOT_COMPLETED and MY_PACKAGE_REPLACED broadcasts.
 * Contract: only schedules; compression remains disabled when preferences contain no enabled folders.
 * Verification: manifest/action wiring readback only; broadcast delivery is UNVERIFIED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AutoCompressScheduler.schedule(context)
        }
    }
}
