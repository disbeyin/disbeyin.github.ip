package com.dersbekcisi.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Bekçi motoru çalışmıyorken (açılışta kilit açılmadan önce, ya da erişilebilirlik hizmeti kapatılınca)
 * alarmı çalan ön plan hizmeti. Motor devreye girince ya da ders saati bitince kendini kapatır.
 */
class AlarmService : Service() {
    companion object {
        @Volatile
        var running = false
            private set

        fun start(ctx: Context, reason: String) {
            try {
                ctx.startForegroundService(Intent(ctx, AlarmService::class.java).putExtra("reason", reason))
            } catch (e: Exception) {
                Journal.add(ctx, "Alarm hizmeti başlatılamadı, doğrudan çalınıyor: $e")
                AlarmPlayer.start(ctx, "service")
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, AlarmService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private val h = Handler(Looper.getMainLooper())

    private val check = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val sch = Schedule(Store.load(this@AlarmService).days)
            val engine = BekciService.instance
            if (!Control.enforcementActive(this@AlarmService, now, sch) || (engine != null && engine.hasTakenOver())) {
                stopSelf()
                return
            }
            h.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra("reason") ?: "Ders saatinde bekçi çalışmıyor"
        Notif.ensure(this)
        try {
            startForeground(Notif.ID_FGS, Notif.fgs(this, reason), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            Journal.add(this, "startForeground hatası: $e")
        }
        running = true
        AlarmPlayer.start(this, "service")
        Journal.add(this, "Alarm hizmeti çalıyor: $reason")
        h.removeCallbacks(check)
        h.postDelayed(check, 5000)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacks(check)
        AlarmPlayer.stop(this, "service")
        super.onDestroy()
    }
}
