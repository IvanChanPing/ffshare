package com.caydey.ffshare.autocompress

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Purpose: records baseline, queued, and replacement document URIs to prevent duplicate work.
 * Invocation: settings seeding and MediaChangeJobService discovery/replacement paths.
 * Contract: URI is the primary key; baseline rows suppress existing files and OUTPUT rows suppress
 * replacement notifications. Database writes are transactional only for folder seeding.
 * Verification: SQL/source readback only; persistence and migration are UNVERIFIED.
 */
class SeenDb(context: Context) : SQLiteOpenHelper(context, "autocompress.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE seen (
                uri TEXT PRIMARY KEY,
                folder_uri TEXT NOT NULL,
                size INTEGER NOT NULL,
                modified INTEGER NOT NULL,
                state TEXT NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun markSeen(uri: String, folderUri: String, size: Long, modified: Long, state: String = "SEEN") {
        val values = ContentValues().apply {
            put("uri", uri)
            put("folder_uri", folderUri)
            put("size", size)
            put("modified", modified)
            put("state", state)
        }
        writableDatabase.insertWithOnConflict("seen", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun contains(uri: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM seen WHERE uri=? LIMIT 1", arrayOf(uri)
    ).use { it.moveToFirst() }

    fun seedFolder(folderUri: String, items: Sequence<MediaCandidate>) {
        writableDatabase.beginTransaction()
        try {
            items.forEach { markSeen(it.uri.toString(), folderUri, it.size, it.modified, "BASELINE") }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }
}
