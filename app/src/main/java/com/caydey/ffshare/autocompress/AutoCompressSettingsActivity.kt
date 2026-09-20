package com.caydey.ffshare.autocompress

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.caydey.ffshare.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Purpose: owns automatic-compression enablement, SAF grants, baseline creation, and folder removal.
 * Invocation: Settings → Automatic compression and the system folder/notification permission pickers.
 * Visual: a scrollable vertical system-controls screen with an enable switch, status text, folder rows,
 * Remove buttons, Add folder, and an explicit direct-children/local-storage explanation.
 * Contract: only local shared-storage trees with persisted read/write grants are accepted. Baseline
 * scanning runs in the lifecycle scope off the UI thread; disable/remove cancels tagged work, and
 * removal releases the grant. Provider and scheduling failures remain visible in the status row.
 * Verification: source/static checks only; real picker, permission prompt, and rendered UI are UNVERIFIED.
 */
class AutoCompressSettingsActivity : ComponentActivity() {
    private lateinit var content: LinearLayout
    @Volatile private var baselineRunning = false

    private val pickFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val uri = data.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFolder(uri, flags)
        }
    }
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { rebuild() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rebuild()
    }

    private fun rebuild() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val scroll = ScrollView(this).apply { addView(content) }

        content.addView(Switch(this).apply {
            text = getString(R.string.auto_compress_enable)
            isChecked = AutoCompressPrefs.isEnabled(this@AutoCompressSettingsActivity)
            isEnabled = !baselineRunning
            setOnCheckedChangeListener { _, checked ->
                AutoCompressPrefs.setEnabled(this@AutoCompressSettingsActivity, checked)
                if (checked) {
                    requestNotificationPermissionIfNeeded()
                    AutoCompressScheduler.schedule(this@AutoCompressSettingsActivity)
                } else {
                    AutoCompressScheduler.cancel(this@AutoCompressSettingsActivity)
                    WorkManager.getInstance(this@AutoCompressSettingsActivity)
                        .cancelAllWorkByTag(AutoCompressWorker.TAG_ALL)
                    AutoCompressPrefs.setStatus(this@AutoCompressSettingsActivity, getString(R.string.auto_compress_disabled))
                }
                rebuild()
            }
        })

        content.addView(TextView(this).apply {
            text = AutoCompressPrefs.status(this@AutoCompressSettingsActivity)
                .ifEmpty { getString(R.string.auto_compress_not_configured) }
            setPadding(0, 12, 0, 20)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.auto_compress_folders)
            textSize = 20f
            setPadding(0, 8, 0, 12)
        })

        AutoCompressPrefs.folders(this).sortedBy(Uri::toString).forEach { uri ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = uri.toString()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = getString(R.string.auto_compress_remove)
                isEnabled = !baselineRunning
                setOnClickListener { removeFolder(uri) }
            })
            content.addView(row)
        }

        content.addView(Button(this).apply {
            text = if (baselineRunning) {
                getString(R.string.auto_compress_adding)
            } else {
                getString(R.string.auto_compress_add)
            }
            isEnabled = !baselineRunning
            setOnClickListener { openFolderPicker() }
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.auto_compress_explanation)
            setPadding(0, 24, 0, 0)
        })
        setContentView(scroll)
    }

    private fun openFolderPicker() {
        pickFolder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        })
    }

    private fun addFolder(uri: Uri, grantedFlags: Int) {
        if (baselineRunning || uri in AutoCompressPrefs.folders(this)) return
        val requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (!FolderScanner.isSupportedLocalTree(uri)) {
            AutoCompressPrefs.setStatus(this, getString(R.string.auto_compress_local_folders_only))
            rebuild()
            return
        }
        if (grantedFlags and requiredFlags != requiredFlags) {
            AutoCompressPrefs.setStatus(this, getString(R.string.auto_compress_folder_not_writable))
            rebuild()
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, requiredFlags)
        } catch (_: SecurityException) {
            AutoCompressPrefs.setStatus(this, getString(R.string.auto_compress_grant_failed))
            rebuild()
            return
        }

        baselineRunning = true
        AutoCompressPrefs.setStatus(this, getString(R.string.auto_compress_baselining))
        rebuild()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val root = DocumentFile.fromTreeUri(this@AutoCompressSettingsActivity, uri)
                        check(root != null && root.isDirectory && root.canRead() && root.canWrite())
                        val items = FolderScanner.scan(this@AutoCompressSettingsActivity, uri).toList()
                        SeenDb(this@AutoCompressSettingsActivity).seedFolder(items.asSequence())
                        AutoCompressPrefs.addFolder(this@AutoCompressSettingsActivity, uri)
                        items.size
                    }
                }
                baselineRunning = false
                if (result.isSuccess) {
                    AutoCompressPrefs.setStatus(
                        this@AutoCompressSettingsActivity,
                        getString(R.string.auto_compress_baseline_complete, result.getOrThrow())
                    )
                    AutoCompressScheduler.schedule(this@AutoCompressSettingsActivity)
                } else {
                    AutoCompressPrefs.setStatus(
                        this@AutoCompressSettingsActivity,
                        getString(R.string.auto_compress_baseline_failed)
                    )
                }
                rebuild()
            } finally {
                // The IO block can commit just as this Activity is destroyed. Read the durable
                // preference instead of a coroutine-local flag before deciding to release access.
                if (uri !in AutoCompressPrefs.folders(this@AutoCompressSettingsActivity)) {
                    runCatching {
                        contentResolver.releasePersistableUriPermission(uri, requiredFlags)
                    }
                }
            }
        }
    }

    private fun removeFolder(uri: Uri) {
        AutoCompressPrefs.removeFolder(this, uri)
        WorkManager.getInstance(this).cancelAllWorkByTag(AutoCompressWorker.folderTag(uri.toString()))
        SeenDb(this).removeFolder(uri.toString())
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.releasePersistableUriPermission(uri, flags) }
        if (AutoCompressPrefs.folders(this).isEmpty()) {
            AutoCompressPrefs.setEnabled(this, false)
            AutoCompressScheduler.cancel(this)
            AutoCompressPrefs.setStatus(this, getString(R.string.auto_compress_not_configured))
        } else {
            AutoCompressScheduler.schedule(this)
        }
        rebuild()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
