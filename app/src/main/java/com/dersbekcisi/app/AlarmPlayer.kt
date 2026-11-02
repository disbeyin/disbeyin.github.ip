package com.dersbekcisi.app

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Siren sesi. Alarm ses akışında, mümkünse tabletin kendi hoparlöründen çalar.
 * Kulaklık bağlıyken ses ayarlardaki yüzdeyle sınırlanır (işitme güvenliği).
 * Birden fazla "sahip" olabilir (motor, alarm hizmeti, test); hepsi bırakınca susar.
 */
object AlarmPlayer {
    private val owners = HashSet<String>()
    private val h = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var appCtx: Context? = null
    private var savedVolume = -1
    private var savedFilter = -1
    private var hpPct = 30
    private var wake: PowerManager.WakeLock? = null
    var routing: String = "-"
        private set

    val playing: Boolean get() = track != null

    fun start(ctx: Context, owner: String) {
        owners.add(owner)
        if (track != null) return
        val c = ctx.applicationContext
        appCtx = c
        hpPct = Store.load(c).headphonePct.coerceIn(5, 100)
        val am = c.getSystemService(AudioManager::class.java) ?: return
        savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        applyVolume(am)
        openDnd(c)
        try {
            val t = buildSiren()
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { t.setPreferredDevice(it) }
            t.play()
            track = t
        } catch (e: Exception) {
            Journal.add(c, "Alarm sesi başlatılamadı: $e")
        }
        try {
            c.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0))
        } catch (_: Exception) {
        }
        try {
            val pm = c.getSystemService(PowerManager::class.java)
            if (pm != null) {
                val w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DersBekcisi:alarm")
                w.setReferenceCounted(false)
                w.acquire(60 * 60_000L)
                wake = w
            }
        } catch (_: Exception) {
        }
        h.removeCallbacks(enforcer)
        h.postDelayed(enforcer, 700)
    }

    fun stop(ctx: Context, owner: String) {
        owners.remove(owner)
        if (owners.isNotEmpty()) return
        val c = ctx.applicationContext
        h.removeCallbacks(enforcer)
        val t = track
        track = null
        if (t != null) {
            try { t.stop() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
            try { c.getSystemService(Vibrator::class.java)?.cancel() } catch (_: Exception) {}
            val am = c.getSystemService(AudioManager::class.java)
            if (am != null && savedVolume >= 0) {
                try { am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) } catch (_: Exception) {}
            }
            savedVolume = -1
            restoreDnd(c)
        }
        wake?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
        wake = null
    }

    private val enforcer = object : Runnable {
        override fun run() {
            val c = appCtx ?: return
            val t = track ?: return
            val am = c.getSystemService(AudioManager::class.java) ?: return
            applyVolume(am)
            try {
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            } catch (_: Exception) {
            }
            val r = try { t.routedDevice } catch (_: Exception) { null }
            routing = if (r == null) "bilinmiyor" else Audio.typeName(r.type)
            h.postDelayed(this, 1500)
        }
    }

    private fun applyVolume(am: AudioManager) {
        val maxV = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val pct = if (Audio.headphonesConnected(am)) hpPct else 100
        val target = max(1, (maxV * pct + 50) / 100)
        try {
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) != target) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            }
        } catch (_: Exception) {
        }
    }

    private fun openDnd(c: Context) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (!nm.isNotificationPolicyAccessGranted) return
            val cur = nm.currentInterruptionFilter
            if (cur == NotificationManager.INTERRUPTION_FILTER_NONE || cur == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                savedFilter = cur
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            }
        } catch (_: Exception) {
        }
    }

    private fun restoreDnd(c: Context) {
        if (savedFilter < 0) return
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(savedFilter)
        } catch (_: Exception) {
        }
        savedFilter = -1
    }

    private fun buildSiren(): AudioTrack {
        val rate = 44100
        val frames = rate * 2
        val pcm = ShortArray(frames)
        var phase = 0.0
        for (i in 0 until frames) {
            val cyc = (i.toDouble() / rate) % 1.0
            val f = if (cyc < 0.5) 650.0 + 1500.0 * cyc else 650.0 + 1500.0 * (1.0 - cyc)
            phase += 2.0 * PI * f / rate
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            val s = sin(phase)
            val v = 0.55 * (if (s >= 0) 1.0 else -1.0) + 0.45 * s
            pcm[i] = (v * 0.92 * Short.MAX_VALUE).toInt().toShort()
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
        t.write(pcm, 0, frames)
        t.setLoopPoints(0, frames, -1)
        return t
    }
}
