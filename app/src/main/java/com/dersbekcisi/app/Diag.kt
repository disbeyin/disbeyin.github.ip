package com.dersbekcisi.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager

/** Sorun olduğunda kopyalanıp yapıştırılacak, kişisel içerik barındırmayan tanı raporu. */
object Diag {
    fun build(ctx: Context): String {
        val now = System.currentTimeMillis()
        val s = Store.load(ctx)
        val sch = Schedule(s.days)
        val sb = StringBuilder()
        sb.append("== Ders Bekçisi tanı raporu ==\n")
        sb.append("Sürüm ").append(Util.versionName(ctx))
            .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")")
            .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" · ").append(Build.DISPLAY).append('\n')
        sb.append("Erişilebilirlik açık: ").append(Util.a11yEnabled(ctx))
            .append(" · hizmet çalışıyor: ").append(BekciService.instance != null).append('\n')
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val alarmMgr = ctx.getSystemService(AlarmManager::class.java)
        val am = ctx.getSystemService(AudioManager::class.java)
        sb.append("Bildirim: ").append(nm?.areNotificationsEnabled())
            .append(" · Pil muaf: ").append(pm?.isIgnoringBatteryOptimizations(ctx.packageName))
            .append(" · Cihaz yöneticisi: ").append(dpm?.isAdminActive(ComponentName(ctx, AdminReceiver::class.java)))
            .append(" · Rahatsız Etmeyin erişimi: ").append(nm?.isNotificationPolicyAccessGranted)
            .append(" · Tam zamanlı alarm: ")
            .append(if (Build.VERSION.SDK_INT < 31) true else alarmMgr?.canScheduleExactAlarms())
            .append('\n')
        sb.append("Kulaklık bağlı: ").append(am?.let { Audio.headphonesConnected(it) })
            .append(" · Alarm çalıyor: ").append(AlarmPlayer.playing)
            .append(" · Alarm hizmeti: ").append(AlarmService.running).append('\n')
        sb.append("Koruma: ").append(Control.armed(ctx))
            .append(" · Bakım: ").append(Control.inMaint(ctx, now))
            .append(" · PIN var: ").append(Control.hasPin(ctx)).append('\n')
        sb.append("Şu an ders saati: ").append(sch.isActive(now))
            .append(" · Sonraki değişim: ").append(sch.describeNext(now)).append('\n')
        sb.append("Saatler: ")
        s.days.forEachIndexed { i, d -> sb.append(Schedule.DAY_SHORT[i]).append('=').append(if (d.isBlank()) "-" else d).append("  ") }
        sb.append('\n')
        sb.append("Ders uygulamaları: ").append(s.studyApps.joinToString(", ")).append('\n')
        sb.append("Yasaklı uygulama sayısı: ").append(s.blockedApps.size)
            .append(" · Anahtar kelime sayısı: ").append(s.keywords.size).append('\n')
        sb.append("Eşikler: kontrol ").append(s.checkSec).append("s, kanıt ").append(s.holdSec)
            .append("s, tolerans ").append(s.graceSec).append("s, uyarı ").append(s.warnSec)
            .append("s, ekran kapalı ").append(s.offGraceSec).append("s, matematik ").append(s.mathThreshold)
            .append(", kelime ").append(s.minKeywords).append(", kişi ").append(s.personMemMin)
            .append("dk/tek başına ").append(s.personAlone).append(", kulaklık %").append(s.headphonePct).append('\n')
        BekciService.instance?.let { sb.append("-- Motor --\n").append(it.describe()).append('\n') }
        sb.append("-- Son kayıtlar --\n")
        Journal.tail(ctx, 80).forEach { sb.append(it).append('\n') }
        return sb.toString()
    }
}
