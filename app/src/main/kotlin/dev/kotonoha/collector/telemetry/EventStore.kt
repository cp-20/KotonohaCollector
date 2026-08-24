package dev.kotonoha.collector.telemetry

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

internal class EventStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    fun interface ResultCallback<T> {
        fun onResult(result: T, error: Exception?)
    }

    private val writer = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cryptoManager = CryptoManager()

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "created_at INTEGER NOT NULL," +
                "event_type TEXT NOT NULL," +
                "nonce BLOB NOT NULL," +
                "payload BLOB NOT NULL)",
        )
        database.execSQL("CREATE INDEX events_created_at ON events(created_at)")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun append(event: CollectionEvent) {
        writer.execute {
            runCatching {
                val encrypted = cryptoManager.encrypt(event.toJson().toString().toByteArray())
                val values = ContentValues().apply {
                    put("created_at", event.timestampMs)
                    put("event_type", event.type)
                    put("nonce", encrypted.nonce)
                    put("payload", encrypted.cipherText)
                }
                writableDatabase.insertOrThrow("events", null, values)
            }.onFailure { Log.e(TAG, "Failed to persist collection event", it) }
        }
    }

    fun count(callback: ResultCallback<Long>) = submit(callback) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    fun exportJsonLines(outputStream: OutputStream, callback: ResultCallback<Long>) = submit(callback) {
        BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { output ->
            readableDatabase.query(
                "events",
                arrayOf("nonce", "payload"),
                null,
                null,
                null,
                null,
                "id ASC",
            ).use { cursor ->
                var exported = 0L
                while (cursor.moveToNext()) {
                    val plainText = cryptoManager.decrypt(cursor.getBlob(0), cursor.getBlob(1))
                    output.write(plainText.toString(Charsets.UTF_8))
                    output.newLine()
                    exported++
                }
                exported
            }
        }
    }

    fun deleteAll(callback: ResultCallback<Long>) = submit(callback) {
        writableDatabase.delete("events", null, null).toLong()
    }

    private fun submit(callback: ResultCallback<Long>, operation: () -> Long) {
        writer.execute {
            val result = runCatching(operation)
            mainHandler.post {
                result.fold(
                    onSuccess = { callback.onResult(it, null) },
                    onFailure = { callback.onResult(0L, it as? Exception ?: Exception(it)) },
                )
            }
        }
    }

    companion object {
        private const val TAG = "KotonohaEventStore"
        private const val DATABASE_NAME = "collection.db"
        private const val DATABASE_VERSION = 1

        @Volatile
        private var instance: EventStore? = null

        fun get(context: Context): EventStore = instance ?: synchronized(this) {
            instance ?: EventStore(context.applicationContext).also { instance = it }
        }
    }
}
