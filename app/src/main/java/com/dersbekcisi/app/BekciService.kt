package com.dersbekcisi.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class Verdict(val tr: String) {
    STRONG("ders içeriği var ✓"),
    WEAK("anlatan kişi var ✓"),
    NONE("ders içeriği yok ✗"),
    BLOCKED("yasaklı uygulama ✗"),
}

/**
 * Bekçinin motoru. Erişilebilirlik hizmeti olarak sürekli çalışır:
 * ders saatinde ekranı izler, ders kanıtı yoksa önce uyarır, sonra alarm çalar.
 */
class BekciService : AccessibilityService() {

    enum class Phase { IDLE, OK, GRACE, WARNING, ALARM }

    companion object {
        @Volatile
        var instance: BekciService? = null
            private set

        private const val LABEL_NORM = "ders bekcisi"
        private val DANGER = listOf(
            "zorla durdur", "durmaya zorla", "force stop", "kaldir", "uninstall", "erisilebilirlik",
            "accessibility", "yonetici", "admin", "devre disi", "disable", "kullan", "bildirim",
            "notification", "pil ", "battery",
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    private var analyzer: Analyzer? = null
    private var overlay: Overlay? = null
    private var pm: PowerManager? = null
    private var km: KeyguardManager? = null
    private var am: AudioManager? = null
    private var vibrator: Vibrator? = null
    private var cpuWake: PowerManager.WakeLock? = null
    private var cleaned = false

    private var settings: Settings? = null
    private var schedule = Schedule(emptyList())
    private var cfgVer = -1L

    var phase = Phase.IDLE
        private set
    private var okUntil = 0L
    private var lastStrongAt = 0L
    private var violationStart = 0L
    private var violationReason = "Ekranda ders içeriği yok"
    private var blockedNow = false
    private var bootFast = false
    private var lastTickAt = 0L
    private var lastPulseAt = 0L
    private var lastAnalysisAt = 0L
    private var busySince = 0L
    private var lastEventPkg: String? = null
    private var lastStudyPkg: String? = null
    private var lastReason = "Henüz analiz yapılmadı"
    private var lastSummary = ""
    private var lastStatusText = ""
    private var lastStatusAt = 0L
    private var testPending = false

    private var pStudy = 0L
    private var pViol = 0L
    private var pAlarms = 0
    private var pWarns = 0
    private var pTamper = 0
    private var lastFlushAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            safeTick()
            main.postDelayed(this, 2000)
        }
    }
    private val soonAnalysis = Runnable {
        try { maybeAnalyze("ekran değişti", false) } catch (t: Throwable) { Journal.add(this, "Analiz hatası: $t") }
    }
    private val guardCheck = Runnable {
        try { runGuard() } catch (t: Throwable) { Journal.add(this, "Koruma hatası: $t") }
    }
    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            main.post { safeTick() }
            if (i.action != Intent.ACTION_SCREEN_OFF) {
                main.removeCallbacks(soonAnalysis)
                main.postDelayed(soonAnalysis, 1500)
            }
        }
    }

    // ---------------------------------------------------------------- yaşam döngüsü

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        cleaned = false
        pm = getSystemService(PowerManager::class.java)
        km = getSystemService(KeyguardManager::class.java)
        am = getSystemService(AudioManager::class.java)
        vibrator = getSystemService(Vibrator::class.java)
        val ov = Overlay(this)
        ov.onBannerClick = { openStudyApp() }
        overlay = ov
        val w = Executors.newSingleThreadExecutor()
        worker = w
        analyzer = Analyzer(this, w)
        Notif.ensure(this)
        val f = IntentFilter()
        f.addAction(Intent.ACTION_SCREEN_OFF)
        f.addAction(Intent.ACTION_SCREEN_ON)
        f.addAction(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenRx, f)
        Journal.add(this, "Bekçi hizmeti başladı")
        Scheduler.reschedule(this)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (cleaned) return
        cleaned = true
        instance = null
        main.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenRx) } catch (_: Exception) {}
        overlay?.removeAll()
        AlarmPlayer.stop(this, "engine")
        setCpuWake(false)
        worker?.shutdownNow()
        analyzer?.close()
        flush(System.currentTimeMillis(), true)
        Journal.add(this, "Bekçi hizmeti durdu")
        val now = System.currentTimeMillis()
        if (Control.enforcementActive(this, now, Schedule(Store.load(this).days))) {
            AlarmService.start(this, "Bekçi hizmeti ders saatinde kapandı")
        }
    }

    // ---------------------------------------------------------------- dışarıya açık

    fun hasTakenOver(): Boolean = phase != Phase.IDLE

    fun onExternalTick() {
        main.post { safeTick() }
    }

    fun requestTestAnalysis(delayMs: Long) {
        testPending = true
        main.postDelayed({
            try { maybeAnalyze("test", true) } catch (t: Throwable) { Journal.add(this, "Test hatası: $t") }
        }, delayMs)
    }

    fun describe(): String {
        val ph = when (phase) {
            Phase.IDLE -> "Beklemede"
            Phase.OK -> "Çalışıyorsun ✓"
            Phase.GRACE -> "Ders içeriği aranıyor…"
            Phase.WARNING -> "UYARI"
            Phase.ALARM -> "ALARM"
        }
        val sb = StringBuilder("Durum: $ph\nSon karar: $lastReason")
        if (lastSummary.isNotEmpty()) sb.append('\n').append(lastSummary)
        if (AlarmPlayer.playing) sb.append("\nAlarm çıkışı: ").append(AlarmPlayer.routing)
        return sb.toString()
    }

    // ---------------------------------------------------------------- olaylar

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        val type = e.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastEventPkg = pkg
            if (phase != Phase.IDLE) {
                main.removeCallbacks(soonAnalysis)
                main.postDelayed(soonAnalysis, 1200)
            }
        }
        if ((type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) &&
            pkg != packageName && isGuardPkg(pkg)
        ) {
            main.removeCallbacks(guardCheck)
            main.postDelayed(guardCheck, 350)
        }
    }

    // ---------------------------------------------------------------- motor

    private fun cfg(): Settings {
        val v = Store.version(this)
        val s = settings
        if (s != null && v == cfgVer) return s
        val n = Store.load(this)
        settings = n
        schedule = Schedule(n.days)
        cfgVer = v
        return n
    }

    private fun safeTick() {
        try {
            onTick()
        } catch (t: Throwable) {
            Journal.add(this, "Motor hatası: $t")
        }
    }

    private fun onTick() {
        val s = cfg()
        val now = System.currentTimeMillis()
        val dt = if (lastTickAt == 0L) 0L else (now - lastTickAt).coerceIn(0L, 10_000L)
        lastTickAt = now
        val active = Control.enforcementActive(this, now, schedule)
        if (!active) {
            if (phase != Phase.IDLE) goIdle(now)
            status(now)
            flush(now, false)
            return
        }
        if (phase == Phase.IDLE) enterWindow(now)

        val screenOn = pm?.isInteractive ?: true
        val locked = km?.isKeyguardLocked ?: false
        setCpuWake(!screenOn)
        overlay?.setKeepAwake(screenOn)

        if (screenOn && !locked) {
            val interval = if (phase == Phase.OK) s.checkSec * 1000L else 6000L
            if (now - lastAnalysisAt >= interval) maybeAnalyze("periyodik", false)
        }

        val ok = screenOn && !locked && !blockedNow && now < okUntil
        if (ok) {
            if (phase != Phase.OK) toOk(now)
            pStudy += dt
        } else {
            val reason = when {
                !screenOn -> "Ekran kapalı"
                locked -> "Ekran kilitli"
                else -> violationReason
            }
            if (violationStart == 0L) {
                violationStart = now
                Journal.add(this, "Ders kanıtı yok: $reason")
            }
            val grace = when {
                bootFast -> 0L
                blockedNow -> 5_000L
                !screenOn || locked -> s.offGraceSec * 1000L
                else -> s.graceSec * 1000L
            }
            val warn = if (bootFast) 0L else s.warnSec * 1000L
            val el = now - violationStart
            when {
                el < grace -> setPhase(Phase.GRACE, now)
                el < grace + warn -> {
                    setPhase(Phase.WARNING, now)
                    val left = ((grace + warn - el + 999) / 1000).toInt()
                    overlay?.showBanner(false, "DERS DIŞI · $left sn içinde derse dön", reason)
                    if (now - lastPulseAt >= 5000) {
                        lastPulseAt = now
                        vibrate(450)
                    }
                    pViol += dt
                }
                else -> {
                    setPhase(Phase.ALARM, now)
                    overlay?.showBanner(true, "ALARM · Ders içeriğine dön", reason)
                    pViol += dt
                }
            }
        }
        status(now)
        flush(now, false)
    }

    private fun enterWindow(now: Long) {
        phase = Phase.GRACE
        violationStart = 0L
        okUntil = 0L
        blockedNow = false
        val bootAt = Prefs.sp(this).getLong("bootAlarmAt", 0L)
        bootFast = bootAt > 0L && now - bootAt < 6 * 3600_000L
        lastAnalysisAt = 0L
        Journal.add(this, "Ders saati: bekçi devrede" + if (bootFast) " (yeniden başlatma sonrası, uyarısız alarm)" else "")
    }

    private fun goIdle(now: Long) {
        phase = Phase.IDLE
        overlay?.removeAll()
        AlarmPlayer.stop(this, "engine")
        if (AlarmService.running) AlarmService.stop(this)
        setCpuWake(false)
        violationStart = 0L
        okUntil = 0L
        blockedNow = false
        bootFast = false
        Prefs.sp(this).edit().putLong("bootAlarmAt", 0L).apply()
        Notif.cancel(this, Notif.ID_WARN)
        Journal.add(this, "Bekçi beklemede (ders saati dışı ya da bakım modu)")
        flush(now, true)
    }

    private fun toOk(now: Long) {
        val prev = phase
        phase = Phase.OK
        overlay?.hideBanner()
        AlarmPlayer.stop(this, "engine")
        if (AlarmService.running) AlarmService.stop(this)
        if (violationStart != 0L && (prev == Phase.WARNING || prev == Phase.ALARM)) {
            Journal.add(this, "Derse dönüldü (${(now - violationStart) / 1000} sn sonra)")
        }
        violationStart = 0L
        if (bootFast) {
            bootFast = false
            Prefs.sp(this).edit().putLong("bootAlarmAt", 0L).apply()
        }
        Notif.cancel(this, Notif.ID_WARN)
        flush(now, true)
    }

    private fun setPhase(p: Phase, now: Long) {
        if (p == phase) return
        val prev = phase
        phase = p
        when (p) {
            Phase.WARNING -> {
                pWarns++
                Notif.warn(this, "Ders dışı!", "Kısa süre içinde ders içeriğine dönmezsen alarm çalacak.")
            }
            Phase.ALARM -> {
                pAlarms++
                AlarmPlayer.start(this, "engine")
                if (AlarmService.running) AlarmService.stop(this)
                Journal.add(this, "ALARM: $violationReason")
            }
            else -> {}
        }
        if (prev == Phase.ALARM && p != Phase.ALARM) AlarmPlayer.stop(this, "engine")
        if (p == Phase.GRACE || p == Phase.OK || p == Phase.IDLE) overlay?.hideBanner()
        flush(now, true)
    }

    // ---------------------------------------------------------------- analiz

    private fun neutral(): Set<String> {
        val n = hashSetOf(packageName, "com.android.systemui", "android")
        Util.imePackage(this)?.let { n.add(it) }
        return n
    }

    private fun maybeAnalyze(why: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (busySince != 0L && now - busySince < 15_000L) return
        if (!force && now - lastAnalysisAt < 2500L) {
            main.removeCallbacks(soonAnalysis)
            main.postDelayed(soonAnalysis, 2600L - (now - lastAnalysisAt))
            return
        }
        val s = cfg()
        if (!force && !Control.enforcementActive(this, now, schedule)) return
        if (pm?.isInteractive == false || km?.isKeyguardLocked == true) return
        lastAnalysisAt = now
        val probe = buildProbe(why)
        val neutral = neutral()
        val nonNeutral = probe.visible.filter { it !in neutral }

        if (probe.focused == packageName) {
            decide(Verdict.NONE, "Ders Bekçisi ekranı açık", now, probe, "Uygulama: Ders Bekçisi")
            return
        }
        val blocked = nonNeutral.firstOrNull { it in s.blockedApps } ?: probe.focused?.takeIf { it in s.blockedApps }
        if (blocked != null) {
            decide(Verdict.BLOCKED, "Yasaklı uygulama: " + Util.label(this, blocked), now, probe,
                "Uygulama: " + Util.label(this, probe.focused))
            return
        }
        val f = probe.focused
        if (f != null && f in s.studyApps && nonNeutral.all { it in s.studyApps }) {
            decide(Verdict.STRONG, "Ders uygulaması: " + Util.label(this, f), now, probe,
                "Uygulama: " + Util.label(this, f))
            return
        }

        val treeHits = TextRules.findKeywords(TextRules.norm(probe.treeText), s.keywordsNorm)
        val treeMath = TextRules.mathScore(probe.treeText)
        if (treeHits.size >= s.minKeywords || treeMath >= s.mathThreshold) {
            finish(probe, treeHits, treeMath, 0, "ekrandaki yazı", null, now)
            return
        }
        val an = analyzer ?: return
        busySince = now
        an.screenshot(probe.bannerPx, probe.mediaActive) { r ->
            busySince = 0L
            val n2 = System.currentTimeMillis()
            if (!r.ok) {
                finish(probe, treeHits, treeMath, 0, "ekrandaki yazı", r.error, n2)
            } else {
                val hits = LinkedHashSet(treeHits)
                hits.addAll(TextRules.findKeywords(TextRules.norm(r.text), s.keywordsNorm))
                finish(probe, hits, maxOf(treeMath, TextRules.mathScore(r.text)), r.faces, "ekran görüntüsü (OCR)", null, n2)
            }
        }
    }

    private fun finish(p: Probe, hits: Set<String>, math: Int, faces: Int, src: String, err: String?, now: Long) {
        val s = cfg()
        val neutral = neutral()
        val nonStudy = p.visible.filter { it !in neutral && it !in s.studyApps }
        val recentStrong = lastStrongAt > 0L && now - lastStrongAt <= s.personMemMin * 60_000L
        val person = faces > 0 && p.mediaActive
        val (v, why) = when {
            hits.size >= s.minKeywords -> Verdict.STRONG to ("Ders kelimesi: " + hits.take(4).joinToString(", "))
            math >= s.mathThreshold -> Verdict.STRONG to "Matematik ifadesi (puan $math)"
            person && (s.personAlone || recentStrong) -> Verdict.WEAK to "Anlatan kişi (yüz + ses)"
            person -> Verdict.NONE to "Konuşan kişi var ama son ${s.personMemMin} dk içinde ders içeriği görülmedi"
            err != null -> Verdict.NONE to "Ekran okunamadı ($err)"
            p.focused != null && p.focused in s.studyApps && nonStudy.isNotEmpty() ->
                Verdict.NONE to ("Bölünmüş ekranda ders dışı uygulama: " + Util.label(this, nonStudy.first()))
            else -> Verdict.NONE to "Ekranda ders içeriği yok"
        }
        val summary = buildString {
            append("Uygulama: ").append(Util.label(this@BekciService, p.focused))
            if (p.pip.isNotEmpty()) append(" (+ pencere içinde pencere)")
            append("\nKaynak: ").append(src)
            append("\nKelimeler: ").append(if (hits.isEmpty()) "yok" else hits.joinToString(", "))
            append("\nMatematik puanı: ").append(math).append(" / eşik ").append(s.mathThreshold)
            append("\nYüz: ").append(faces).append(" · ses: ").append(if (p.mediaActive) "var" else "yok")
        }
        decide(v, why, now, p, summary)
    }

    private fun decide(v: Verdict, why: String, now: Long, p: Probe, summary: String) {
        val s = cfg()
        lastReason = v.tr + " — " + why
        lastSummary = summary
        blockedNow = v == Verdict.BLOCKED
        when (v) {
            Verdict.STRONG -> {
                lastStrongAt = now
                okUntil = now + s.holdSec * 1000L
                p.focused?.let { if (it !in neutral()) lastStudyPkg = it }
            }
            Verdict.WEAK -> okUntil = now + s.holdSec * 1000L
            Verdict.BLOCKED -> {
                okUntil = 0L
                violationReason = why
            }
            Verdict.NONE -> violationReason = why
        }
        if (testPending && p.why == "test") {
            testPending = false
            Notif.test(this, "Analiz sonucu: " + v.tr, why + "\n" + summary)
        }
        main.post { safeTick() }
    }

    private fun buildProbe(why: String): Probe {
        val visible = ArrayList<String>()
        val pip = ArrayList<String>()
        var focused: String? = null
        val sb = StringBuilder()
        val budget = intArrayOf(2500)
        val ws: List<AccessibilityWindowInfo> = try { windows ?: emptyList() } catch (_: Exception) { emptyList() }
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = try { w.root } catch (_: Exception) { null } ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (pkg == packageName) {
                if (w.isFocused) focused = pkg
                continue
            }
            if (pkg !in visible) visible.add(pkg)
            if (w.isInPictureInPictureMode && pkg !in pip) pip.add(pkg)
            if (w.isFocused) focused = pkg else if (focused == null && w.isActive) focused = pkg
            collectText(root, sb, budget)
        }
        if (focused == null) focused = lastEventPkg
        return Probe(focused, visible, pip, sb.toString(), am?.isMusicActive ?: false, overlay?.bannerHeight ?: 0, why)
    }

    private fun collectText(root: AccessibilityNodeInfo, sb: StringBuilder, budget: IntArray) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && budget[0] > 0) {
            val n = stack.removeLast()
            budget[0]--
            if (n.isVisibleToUser) {
                val t = n.text
                if (!t.isNullOrBlank()) sb.append(t).append('\n')
                val d = n.contentDescription
                if (!d.isNullOrBlank()) sb.append(d).append('\n')
            }
            for (i in n.childCount - 1 downTo 0) {
                val ch = try { n.getChild(i) } catch (_: Exception) { null }
                if (ch != null) stack.addLast(ch)
            }
        }
    }

    // ---------------------------------------------------------------- koruma

    private fun isGuardPkg(pkg: String): Boolean =
        pkg.contains("settings") || pkg.contains("packageinstaller") || pkg.contains("permissioncontroller") ||
            pkg.contains("safecenter") || pkg.contains("security")

    private fun runGuard() {
        val now = System.currentTimeMillis()
        if (!Control.protectionActive(this, now)) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName || !isGuardPkg(pkg)) return
        val sb = StringBuilder()
        collectText(root, sb, intArrayOf(1200))
        val t = TextRules.norm(sb.toString())
        if (!t.contains(LABEL_NORM)) return
        if (DANGER.none { t.contains(it) }) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        pTamper++
        Journal.add(this, "Koruma: '$pkg' ekranı kapatıldı")
        try {
            Toast.makeText(this, "Koruma açık. Kapatmak için Ders Bekçisi'nden kilidi aç (PIN ya da 10 dk bekle).", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
        vibrate(300)
        flush(now, true)
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun openStudyApp() {
        val s = cfg()
        val candidates = listOfNotNull(lastStudyPkg) + s.studyApps.toList()
        for (p in candidates) {
            val i = packageManager.getLaunchIntentForPackage(p) ?: continue
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    private fun setCpuWake(on: Boolean) {
        try {
            if (on) {
                val p = pm ?: return
                val w = cpuWake ?: p.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DersBekcisi:engine").also {
                    it.setReferenceCounted(false)
                    cpuWake = it
                }
                if (!w.isHeld) w.acquire(4 * 3600_000L)
            } else {
                cpuWake?.let { if (it.isHeld) it.release() }
            }
        } catch (_: Exception) {
        }
    }

    private fun vibrate(ms: Long) {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun status(now: Long) {
        val text = when (phase) {
            Phase.IDLE -> when {
                !Control.armed(this) -> "Koruma kapalı"
                Control.inMaint(this, now) -> "Bakım modu (bekçi duraklatıldı)"
                schedule.isEmpty() -> "Ders saati tanımlı değil"
                else -> "Ders saati değil · sonraki değişim: " + schedule.describeNext(now)
            }
            Phase.OK -> "Çalışıyorsun ✓"
            Phase.GRACE -> "Ders içeriği aranıyor…"
            Phase.WARNING -> "UYARI: derse dön"
            Phase.ALARM -> "ALARM: derse dön"
        }
        if (text == lastStatusText && now - lastStatusAt < 5 * 60_000L) return
        lastStatusText = text
        lastStatusAt = now
        Notif.status(this, text)
    }

    private fun flush(now: Long, force: Boolean) {
        if (!force && now - lastFlushAt < 30_000L) return
        lastFlushAt = now
        if (pStudy == 0L && pViol == 0L && pAlarms == 0 && pWarns == 0 && pTamper == 0) return
        val a = pStudy
        val b = pViol
        val c = pAlarms
        val d = pWarns
        val e = pTamper
        pStudy = 0L
        pViol = 0L
        pAlarms = 0
        pWarns = 0
        pTamper = 0
        Stats.bump(this, now) {
            it.studyMs += a
            it.violMs += b
            it.alarms += c
            it.warnings += d
            it.tamper += e
        }
    }
}
