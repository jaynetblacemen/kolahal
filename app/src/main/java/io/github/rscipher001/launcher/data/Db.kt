package io.github.rscipher001.launcher.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Single SQLite database shared by the UI process and the `:script` process.
 * WAL mode plus SQLite's file locking make concurrent access from both processes safe.
 */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                kind TEXT NOT NULL,
                source TEXT,
                text TEXT NOT NULL,
                ctx TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS logs_kind_ts ON logs(kind, ts)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scripts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                lang TEXT NOT NULL,
                code TEXT NOT NULL,
                interval_min INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 1,
                notify INTEGER NOT NULL DEFAULT 0,
                last_run INTEGER,
                last_status TEXT,
                at_times TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS kv (
                scope TEXT NOT NULL,
                k TEXT NOT NULL,
                v TEXT,
                PRIMARY KEY (scope, k)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE scripts ADD COLUMN at_times TEXT")
    }

    companion object {
        private const val NAME = "kolahal.db"
        private const val VERSION = 2

        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) { instance ?: Db(context).also { instance = it } }
    }
}
