package com.example.anxietytracker

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
import java.util.concurrent.TimeUnit

/**
 * Writes the full CSV to a user-chosen document (picked via the Storage Access Framework,
 * typically inside the Google Drive provider). Drive uploads the file on its own.
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

    /** Throws SecurityException / IOException on failure. */
    @Synchronized
    fun write(ctx: Context, uriString: String) {
        val cr = ctx.contentResolver
        val uri = Uri.parse(uriString)
        val bytes = Csv.build(Store.entries.value, Store.csvSettings()).toByteArray(Charsets.UTF_8)
        val stream: OutputStream? = try {
            cr.openOutputStream(uri, "wt")
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            cr.openOutputStream(uri, "w")
        }
        val out = stream ?: throw FileNotFoundException("no output stream")
        out.use { it.write(bytes) }
    }
}

class CsvSyncWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        Store.init(applicationContext)
        val uri = Store.syncUri.value ?: return Result.success()
        return try {
            CsvSync.write(applicationContext, uri)
            Store.setSyncResult("OK")
            Result.success()
        } catch (e: SecurityException) {
            Store.setSyncResult("Failed: permission lost — choose the file again")
            Result.success()
        } catch (e: FileNotFoundException) {
            Store.setSyncResult("Failed: file not found — choose the file again")
            Result.success()
        } catch (e: IOException) {
            Store.setSyncResult("Failed: ${e.message ?: "I/O error"} (will retry)")
            Result.retry()
        }
    }
}
