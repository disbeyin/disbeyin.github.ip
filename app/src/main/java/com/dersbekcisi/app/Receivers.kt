package com.dersbekcisi.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import kotlin.math.abs

/** Ders saati başlangıç/bitişlerinde ve ders saatinde 5 dakikada bir uygulamayı uyandıran tam zamanlı alarm. */
object Scheduler {
    fun reschedule(ctx: Context) {
        try {
            val now = System.currentTimeMillis()
            val sch = Schedule(Store.load(ctx).days)
            var next = sch.nextChange(now)?.plus(2000L) ?: (now + 6 * 3600_000L)
            if (sch.isActive(now) && Control.armed(ctx)) next = minOf(next, now + 5 * 60_000L)
            val mu = Control.maintUntil(ctx)
            if (mu > now) next = minOf(next, mu + 1500L)
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                ctx, 7, Intent(ctx, TickReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            }
        } catch (t: Throwable) {
            Journal.add(ctx, "Zamanlama hatası: $t")
        }
    }
}

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context
        try {
            val now = System.currentTimeMillis()
            val sch = Schedule(Store.load(ctx).days)
            val active = Control.enforcementActive(ctx, now, sch)
            val sp = Prefs.sp(ctx)
            val wasActive = sp.getBoolean("wasActive", false)
            if (wasActive && !sch.isActive(now) && !sch.hasMoreToday(now)) Notif.report(ctx, now)
            sp.edit().putBoolean("wasActive", active).apply()

            val engine = BekciService.instance
            if (engine != null) {
                engine.onExternalTick()
            } else if (active) {
                Journal.add(ctx, "Ders saati ama bekçi hizmeti çalışmıyor: alarm")
                Notif.warn(ctx, "Ders Bekçisi çalışmıyor!", "Erişilebilirlikte Ders Bekçisi'ni aç. Açılana kadar alarm çalar.")
                AlarmService.start(ctx, "Bekçi hizmeti kapalı")
            }
        } catch (t: Throwable) {
            Journal.add(ctx, "Tick hatası: $t")
        }
        Scheduler.reschedule(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context
        val action = intent.action ?: return
        Scheduler.reschedule(ctx)
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        try {
            val now = System.currentTimeMillis()
            val bootAt = now - SystemClock.elapsedRealtime()
            val sp = Prefs.sp(ctx)
            if (abs(bootAt - sp.getLong("lastBootAt", 0L)) < 60_000L) return
            sp.edit().putLong("lastBootAt", bootAt).commit()
            val sch = Schedule(Store.load(ctx).days)
            if (Control.enforcementActive(ctx, now, sch)) {
                sp.edit().putLong("bootAlarmAt", now).commit()
                Stats.bump(ctx, now) { it.reboots++ }
                Journal.add(ctx, "Ders saatinde yeniden başlatma: alarm hemen başlıyor")
                AlarmService.start(ctx, "Ders saatinde tablet yeniden başlatıldı")
            } else {
                Journal.add(ctx, "Tablet açıldı (ders saati değil)")
            }
        } catch (t: Throwable) {
            Journal.add(ctx, "Açılış hatası: $t")
        }
    }
}

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Journal.add(context, "Silmeye karşı koruma (cihaz yöneticisi) açıldı")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        Journal.add(context, "Cihaz yöneticisini kapatma isteği")
        Stats.bump(context, System.currentTimeMillis()) { it.tamper++ }
        return "Ders Bekçisi'nin silinmeye karşı korumasını kaldırıyorsun. Bu deneme rapora yazıldı."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Journal.add(context, "Cihaz yöneticisi kapatıldı")
    }
}
