package com.example.anxietytracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object Reminders {
    private const val CHANNEL = "reminders"
    private const val NOTIF_ID = 1
    private const val WORK = "reminder_work"
    const val ACTION_SAME = "com.example.anxietytracker.SAME_AS_LAST"
    const val START_HOUR = 8
    const val END_HOUR = 22   // exclusive

    /** hours == 0 means off. */
    fun schedule(ctx: Context, hours: Int, replace: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (hours <= 0) { wm.cancelUniqueWork(WORK); cancelNotification(ctx); return }
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(hours.toLong(), TimeUnit.HOURS,
            15, TimeUnit.MINUTES)
            .setInitialDelay(hours.toLong(), TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(WORK,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancelNotification(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)

    fun canNotify(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    fun show(ctx: Context) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Log reminders",
                NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val same = PendingIntent.getBroadcast(ctx, 1,
            Intent(ctx, QuickLogReceiver::class.java).setAction(ACTION_SAME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("How's your anxiety?")
            .setContentText("Tap to log, or keep the same as last time.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60 * 1000L)
            .setOnlyAlertOnce(true)
            .addAction(0, "Same as last", same)
            .addAction(0, "Open", open)
            .build()
        nm.notify(NOTIF_ID, n)
    }
}

class ReminderWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        Store.init(applicationContext)
        val h = LocalTime.now().hour
        if (h < Reminders.START_HOUR || h >= Reminders.END_HOUR) return Result.success()
        // Skip if the user logged within the last hour.
        val last = Store.entries.value.lastOrNull()?.ts
        if (last != null && System.currentTimeMillis() - last < 60 * 60 * 1000L) return Result.success()
        Reminders.show(applicationContext)
        return Result.success()
    }
}

class QuickLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Reminders.ACTION_SAME) return
        Store.init(context)
        Store.logDraft(context)
        Toast.makeText(context, "Logged (same as last)", Toast.LENGTH_SHORT).show()
    }
}
