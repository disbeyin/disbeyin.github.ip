package com.dersbekcisi.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notif {
    private const val CH_STATUS = "status"
    private const val CH_WARN = "warn"
    private const val CH_REPORT = "report"
    private const val CH_FGS = "fgs"

    const val ID_STATUS = 11
    const val ID_WARN = 12
    const val ID_REPORT = 13
    const val ID_FGS = 14
    const val ID_TEST = 15

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Durum", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WARN, "Uyarılar", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(NotificationChannel(CH_REPORT, "Günlük rapor", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(
            NotificationChannel(CH_FGS, "Alarm hizmeti", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun build(ctx: Context, ch: String, title: String, text: String, icon: Int, ongoing: Boolean): Notification =
        Notification.Builder(ctx, ch)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .setShowWhen(!ongoing)
            .build()

    private fun show(ctx: Context, id: Int, n: Notification) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
        } catch (_: Exception) {
        }
    }

    fun cancel(ctx: Context, id: Int) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(id)
        } catch (_: Exception) {
        }
    }

    fun status(ctx: Context, text: String) =
        show(ctx, ID_STATUS, build(ctx, CH_STATUS, "Ders Bekçisi", text, android.R.drawable.ic_menu_view, true))

    fun warn(ctx: Context, title: String, text: String) =
        show(ctx, ID_WARN, build(ctx, CH_WARN, title, text, android.R.drawable.ic_dialog_alert, false))

    fun test(ctx: Context, title: String, text: String) =
        show(ctx, ID_TEST, build(ctx, CH_WARN, title, text, android.R.drawable.ic_dialog_info, false))

    fun fgs(ctx: Context, text: String): Notification =
        build(ctx, CH_FGS, "Ders Bekçisi alarmı", text, android.R.drawable.ic_lock_idle_alarm, true)

    fun report(ctx: Context, now: Long) {
        val sp = Prefs.sp(ctx)
        val key = Stats.dayKey(now)
        if (sp.getString("lastReport", null) == key) return
        sp.edit().putString("lastReport", key).apply()
        val text = Stats.summary(Stats.load(ctx, now))
        show(ctx, ID_REPORT, build(ctx, CH_REPORT, "Bugünün ders raporu", text, android.R.drawable.ic_menu_agenda, false))
        Journal.add(ctx, "Günlük rapor: $text")
    }
}
