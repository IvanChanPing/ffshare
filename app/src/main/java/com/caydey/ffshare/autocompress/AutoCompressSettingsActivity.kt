package com.caydey.ffshare.autocompress

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Purpose: displays the Automatic compression screen and owns folder selection/baseline seeding.
 * Invocation: Settings → Automatic compression; the visible controls are a switch, Add folder,
 * and Remove buttons in a vertical list.
 * Visual: plain Android vertical layout with a switch at top, folder rows, and text buttons.
 * Contract: OpenDocumentTree grants persistable access, seeds existing files, then schedules the watcher.
 * Verification: source wiring readback only; real picker and UI click path are UNVERIFIED.
 */
class AutoCompressSettingsActivity : ComponentActivity() {

    private lateinit var root: LinearLayout

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rebuild()
    }

    private fun rebuild() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val enabled = Switch(this).apply {
            text = "Automatically compress new files"
            isChecked = AutoCompressPrefs.isEnabled(this@AutoCompressSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AutoCompressPrefs.setEnabled(this@AutoCompressSettingsActivity, checked)
                if (checked) AutoCompressScheduler.schedule(this@AutoCompressSettingsActivity)
                else AutoCompressScheduler.cancel(this@AutoCompressSettingsActivity)
            }
        }
        root.addView(enabled)

        root.addView(TextView(this).apply {
            text = "Watched folders"
            textSize = 20f
            setPadding(0, 28, 0, 12)
        })

        AutoCompressPrefs.folders(this).forEach { uri ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = uri.toString()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "Remove"
                setOnClickListener {
                    AutoCompressPrefs.removeFolder(this@AutoCompressSettingsActivity, uri)
                    rebuild()
                    AutoCompressScheduler.schedule(this@AutoCompressSettingsActivity)
                }
            })
            root.addView(row)
        }

        root.addView(Button(this).apply {
            text = "Add folder"
            setOnClickListener { pickFolder.launch(null) }
        })

        root.addView(TextView(this).apply {
            text = "New media in these folders will use FFShare's normal compression settings, then replace the original with the compressed file using the exact same filename and folder."
            setPadding(0, 24, 0, 0)
        })

        setContentView(root)
    }

    private fun addFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            return
        }

        // Baseline current contents so enabling a folder only affects files created later.
        val db = SeenDb(this)
        db.seedFolder(uri.toString(), FolderScanner.scan(this, uri))
        AutoCompressPrefs.addFolder(this, uri)
        rebuild()
        AutoCompressScheduler.schedule(this)
    }
}
