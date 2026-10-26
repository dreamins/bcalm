package com.example.anxietytracker

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Two-way sync with a user-chosen document (picked via the Storage Access Framework, typically inside the
 * Google Drive provider): outside edits are merged in, then the full CSV is written back. Drive uploads the file on its own.
 */
object CsvSync {
    private const val WORK = "csv-sync"
    private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun enqueueIfEnabled(ctx: Context) {
        if (Store.syncUri.value != null) enqueue(ctx)
    }

    fun enqueue(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<CsvSyncWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    fun setTarget(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, RW)
        } catch (e: Exception) {
            // some providers do not offer persistable grants; syncing may still work for now
        }
        Store.setSyncUri(uri.toString())
        enqueue(ctx)
    }

    /** Points sync at an existing document; the first sync merges its content in (setSyncUri clears syncHash). */
    fun setExistingTarget(ctx: Context, uri: Uri) {
        var err: String? = null
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, RW)
        } catch (e: Exception) {
            err = "Failed: could not keep write access to that file (${e.message ?: e.javaClass.simpleName})"
        }
        Store.setSyncUri(uri.toString())
        if (err != null) Store.setSyncResult(err) else enqueue(ctx)
    }

    const val MISSING_MSG = "Sync file missing or no access — choose it again in Settings."

    fun stop(ctx: Context) {
        val s = Store.syncUri.value
        if (s != null) {
            try {
                ctx.contentResolver.releasePersistableUriPermission(Uri.parse(s), RW)
            } catch (e: Exception) {
                // already released or never granted
            }
        }
        WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(WORK)
        Store.setSyncUri(null)
    }

    fun displayName(ctx: Context, uriString: String): String {
        try {
            ctx.contentResolver.query(Uri.parse(uriString), arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        } catch (e: Exception) {
            // fall through
        }
        return "selected file"
    }

    private fun sha(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Current bytes of the document, or null if missing/unreadable (permission loss is rethrown). */
    private fun read(cr: ContentResolver, uri: Uri): ByteArray? = try {
        (cr.openInputStream(uri) ?: throw FileNotFoundException("cannot open")).use { it.readBytes() }
    } catch (e: SecurityException) {
        throw e
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Read-merge-write: if the file differs from what the app last wrote, merge its entries/cards in
     * (file wins), then write the full CSV. Returns true if the merge changed local data.
     * Throws SecurityException / IOException on failure.
     */
    @Synchronized
    fun sync(ctx: Context, uriString: String): Boolean {
        val cr = ctx.contentResolver
        val uri = Uri.parse(uriString)
        val cur = read(cr, uri)?.takeIf { it.isNotEmpty() }
        var merged = false
        if (cur != null && sha(cur) != Store.syncHash) {
            val text = String(cur, Charsets.UTF_8)
            merged = Store.mergeFromFile(Csv.parse(text).orEmpty(), Csv.parseSettings(text))
        }
        val bytes = Csv.build(Store.entries.value, Store.csvSettings()).toByteArray(Charsets.UTF_8)
        val hash = sha(bytes)
        if (cur == null || sha(cur) != hash) {   // identical content: skip the write (no needless Drive upload)
            val stream: OutputStream? = try {
                cr.openOutputStream(uri, "wt")
            } catch (e: SecurityException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                cr.openOutputStream(uri, "w")
            }
            val out = stream ?: throw FileNotFoundException("no output stream")
            out.use { it.write(bytes) }
        }
        Store.syncHash = hash
        return merged
    }
}

class CsvSyncWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        Store.init(applicationContext)
        val uri = Store.syncUri.value ?: return Result.success()
        return try {
            val merged = CsvSync.sync(applicationContext, uri)
            Store.setSyncResult(if (merged) "Synced (merged changes from file)" else "OK")
            Result.success()
        } catch (e: SecurityException) {
            Store.setSyncResult(CsvSync.MISSING_MSG)
            Result.success()
        } catch (e: IllegalArgumentException) {
            Store.setSyncResult(CsvSync.MISSING_MSG)
            Result.success()
        } catch (e: FileNotFoundException) {
            Store.setSyncResult(CsvSync.MISSING_MSG)
            Result.success()
        } catch (e: IOException) {
            Store.setSyncResult("Failed: ${e.message ?: "I/O error"} (will retry)")
            Result.retry()
        }
    }
}
