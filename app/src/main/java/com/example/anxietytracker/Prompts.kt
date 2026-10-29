package com.example.anxietytracker

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.LocalTime
import kotlin.random.Random

/** Prompted check-ins: one random notification per daily window, planned for today and tomorrow. */
object Prompts {
    private const val CHANNEL = "check-ins"
    private const val NOTIF_BASE = 100          // + window
    const val ACTION_FIRE = "com.example.anxietytracker.PROMPT_FIRE"
    const val ACTION_SKIP = "com.example.anxietytracker.PROMPT_SKIP"
    const val EXTRA_ID = "prompt_id"
    const val VALID_MS = 60 * 60_000L
    const val WINDOWS = 3
    val NAMES = listOf("Morning", "Afternoon", "Evening")
    val DEFAULTS = listOf(9 * 60 to 11 * 60, 14 * 60 to 16 * 60, 19 * 60 to 21 * 60)

    fun canNotify(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    fun isPaused(date: LocalDate) = date.toEpochDay() < Store.promptsPausedUntil

    private fun dateOf(rec: PromptRec) = java.time.Instant.ofEpochMilli(rec.scheduledTs).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun tsOf(date: LocalDate, minute: Int) =
        ZonedDateTime.of(date, LocalTime.of(minute / 60, minute % 60), ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** One-time cleanup of the old interval reminder (WorkManager job + its notification). */
    fun migrate(ctx: Context) {
        if (Store.promptsMigrated) return
        WorkManager.getInstance(ctx).cancelUniqueWork("reminder_work")
        NotificationManagerCompat.from(ctx).cancel(1)
        Store.promptsMigrated = true
    }

    /**
     * Brings plan and alarms in line with settings: expires stale prompts, drops future pending ones that are off/paused
     * (or all of them when [reroll], e.g. after the windows changed), creates missing records for today and tomorrow,
     * and sets an alarm for every future pending prompt. Existing records are never rerolled otherwise.
     */
    @Synchronized
    fun plan(ctx: Context, reroll: Boolean = false, now: Long = System.currentTimeMillis()) {
        Store.init(ctx)
        Store.expireStale(now)
        val on = Store.promptsOn
        for (p in Store.prompts.value) {
            if (p.status == PROMPT_PENDING && p.scheduledTs > now && (!on || reroll || isPaused(dateOf(p)))) {
                cancelAlarm(ctx, p.id)
                Store.removePrompt(p.id)
            }
        }
        if (on) {
            val today = LocalDate.now()
            for (date in listOf(today, today.plusDays(1))) {
                if (isPaused(date)) continue
                for (w in 0 until WINDOWS) {
                    val id = promptId(date, w)
                    if (Store.prompts.value.any { it.id == id }) continue
                    val (s, e) = Store.promptWindow(w)
                    val ts = tsOf(date, Random.nextInt(s, e))
                    if (date == today && tsOf(date, e) <= now) continue   // window already over: not sampled today
                    Store.upsertPrompt(PromptRec(id, ts, w, PROMPT_PENDING))
                }
            }
        }
        for (p in Store.prompts.value) if (p.status == PROMPT_PENDING && p.scheduledTs > now) setAlarm(ctx, p)
    }

    private fun pi(ctx: Context, id: String, action: String, flags: Int = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) =
        PendingIntent.getBroadcast(ctx, id.hashCode(), Intent(ctx, PromptReceiver::class.java).setAction(action)
            .setData(Uri.parse("bcalm://prompt/$id/$action")).putExtra(EXTRA_ID, id), flags)

    private fun setAlarm(ctx: Context, p: PromptRec) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setWindow(AlarmManager.RTC_WAKEUP, p.scheduledTs, 5 * 60_000L, pi(ctx, p.id, ACTION_FIRE))
    }

    private fun cancelAlarm(ctx: Context, id: String) {
        val p = PendingIntent.getBroadcast(ctx, id.hashCode(), Intent(ctx, PromptReceiver::class.java).setAction(ACTION_FIRE)
            .setData(Uri.parse("bcalm://prompt/$id/$ACTION_FIRE")).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) ?: return
        ctx.getSystemService(AlarmManager::class.java).cancel(p)
        p.cancel()
    }

    fun cancelNotification(ctx: Context, window: Int) = NotificationManagerCompat.from(ctx).cancel(NOTIF_BASE + window)

    /** Alarm fired for prompt [id]: notify if it is still pending and prompts are allowed, then plan ahead. */
    fun fire(ctx: Context, id: String) {
        Store.init(ctx)
        val now = System.currentTimeMillis()
        Store.expireStale(now)
        val p = Store.prompts.value.firstOrNull { it.id == id }
        if (p != null && p.status == PROMPT_PENDING && Store.promptsOn && !isPaused(dateOf(p)) &&
            now - p.scheduledTs < VALID_MS && canNotify(ctx)) show(ctx, p, now)
        plan(ctx)
    }

    private fun show(ctx: Context, p: PromptRec, now: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Check-ins", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(ctx, p.id.hashCode(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ID, p.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("How's your anxiety right now?")
            .setContentText("Tap to rate 1–10. Takes a second.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter((VALID_MS - (now - p.scheduledTs)).coerceAtLeast(60_000L))
            .setOnlyAlertOnce(true)
            .setDeleteIntent(pi(ctx, p.id, ACTION_SKIP))
            .addAction(0, "Not now", pi(ctx, p.id, ACTION_SKIP))
            .build()
        nm.notify(NOTIF_BASE + p.window, n)
    }

    /** "Not now" or swipe-away: a still-pending prompt becomes skipped. */
    fun skip(ctx: Context, id: String) {
        Store.init(ctx)
        val p = Store.prompts.value.firstOrNull { it.id == id } ?: return
        if (p.status == PROMPT_PENDING) Store.setPromptStatus(id, PROMPT_SKIPPED)
        cancelNotification(ctx, p.window)
    }
}

/** Alarm, "Not now" and swipe-away broadcasts for check-ins (not exported). */
class PromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Prompts.EXTRA_ID)
        when (intent.action) {
            Prompts.ACTION_FIRE -> if (id != null) Prompts.fire(context, id)
            Prompts.ACTION_SKIP -> if (id != null) Prompts.skip(context, id)
        }
    }
}

/** System broadcasts (boot, app update, clock/timezone change) that only trigger a re-plan; reads nothing from the intent. */
class PromptBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Prompts.plan(context)
        }
    }
}
