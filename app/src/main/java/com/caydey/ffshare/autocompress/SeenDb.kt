package com.caydey.ffshare.autocompress

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Purpose: persists each folder/name snapshot and bounded processing state to prevent duplicate work.
 * Invocation: settings baseline seeding, discovery claims/reconciliation, workers, and folder removal.
 * Contract: folder plus exact display name is the durable identity because SAF rename may change URIs.
 * Only an unchanged baseline/active/output/recovery snapshot is suppressed; state transitions include
 * size and modification time so a stale worker cannot overwrite a newer generation. Failures can retry
 * three times, and rows absent from a current directory enumeration are removed.
 * Verification: SQL/source readback only; persistence and migration are UNVERIFIED.
 */
class SeenDb(context: Context) : SQLiteOpenHelper(context, "autocompress.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE seen (
                folder_uri TEXT NOT NULL,
                name TEXT NOT NULL,
                source_uri TEXT NOT NULL,
                size INTEGER NOT NULL,
                modified INTEGER NOT NULL,
                state TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                updated INTEGER NOT NULL DEFAULT 0,
                detail TEXT,
                PRIMARY KEY(folder_uri, name)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // This feature had not shipped before schema 3. Rebuild the internal baseline instead of
        // carrying forward URI-keyed rows whose identity is invalid after a provider rename.
        db.execSQL("DROP TABLE IF EXISTS seen")
        onCreate(db)
    }

    fun markSeen(item: MediaCandidate, state: String) {
        val values = ContentValues().apply {
            put("folder_uri", item.folderUri.toString())
            put("name", item.name)
            put("source_uri", item.uri.toString())
            put("size", item.size)
            put("modified", item.modified)
            put("state", state)
            put("updated", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("seen", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Atomically claims a new snapshot while allowing bounded retry of transient failures. */
    fun claim(item: MediaCandidate): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var state: String? = null
            var size = -1L
            var modified = -1L
            var attempts = 0
            db.rawQuery(
                "SELECT state,size,modified,attempts FROM seen WHERE folder_uri=? AND name=?",
                arrayOf(item.folderUri.toString(), item.name)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    state = cursor.getString(0)
                    size = cursor.getLong(1)
                    modified = cursor.getLong(2)
                    attempts = cursor.getInt(3)
                }
            }
            val sameSnapshot = size == item.size && modified == item.modified
            val suppress = sameSnapshot && state in setOf(
                "BASELINE", "QUEUED", "RUNNING", "OUTPUT", "SKIPPED", "RECOVERY_NEEDED"
            )
            if (suppress || (sameSnapshot && state == "FAILED" && attempts >= MAX_ATTEMPTS)) return false

            val values = ContentValues().apply {
                put("folder_uri", item.folderUri.toString())
                put("name", item.name)
                put("source_uri", item.uri.toString())
                put("size", item.size)
                put("modified", item.modified)
                put("state", "QUEUED")
                put("attempts", if (sameSnapshot) attempts else 0)
                put("updated", System.currentTimeMillis())
                putNull("detail")
            }
            db.insertWithOnConflict("seen", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Purpose: atomically moves one exact queued or retrying snapshot into RUNNING.
     * Invocation: AutoCompressWorker calls this before allocating or touching source/output files.
     * Contract: folder, name, size, and modification time must all still match; stale requests are no-ops.
     * Verification: SQL predicate readback only; concurrent SQLite execution is device-unverified.
     */
    fun tryStart(item: MediaCandidate): Boolean {
        val values = ContentValues().apply {
            put("state", "RUNNING")
            put("updated", System.currentTimeMillis())
            putNull("detail")
        }
        return writableDatabase.update(
            "seen",
            values,
            "folder_uri=? AND name=? AND size=? AND modified=? AND state IN ('QUEUED','FAILED')",
            arrayOf(
                item.folderUri.toString(),
                item.name,
                item.size.toString(),
                item.modified.toString()
            )
        ) == 1
    }

    /** Updates only the same generation so a cancelled stale worker cannot overwrite a newer claim. */
    fun markState(
        item: MediaCandidate,
        state: String,
        detail: String? = null,
        incrementAttempt: Boolean = false
    ) {
        val values = ContentValues().apply {
            put("state", state)
            put("updated", System.currentTimeMillis())
            put("detail", detail)
        }
        val where = "folder_uri=? AND name=? AND size=? AND modified=?"
        val args = arrayOf(
            item.folderUri.toString(),
            item.name,
            item.size.toString(),
            item.modified.toString()
        )
        writableDatabase.update("seen", values, where, args)
        if (incrementAttempt) writableDatabase.execSQL(
            "UPDATE seen SET attempts=attempts+1 WHERE $where",
            args
        )
    }

    fun removeFolder(folderUri: String) {
        writableDatabase.delete("seen", "folder_uri=?", arrayOf(folderUri))
    }

    fun reconcileFolder(folderUri: String, currentNames: Set<String>) {
        val stale = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT name FROM seen WHERE folder_uri=? AND state!='RECOVERY_NEEDED'",
            arrayOf(folderUri)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0).takeIf { it !in currentNames }?.let(stale::add)
            }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            stale.forEach { db.delete("seen", "folder_uri=? AND name=?", arrayOf(folderUri, it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun seedFolder(items: Sequence<MediaCandidate>) {
        writableDatabase.beginTransaction()
        try {
            items.forEach { markSeen(it, "BASELINE") }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
