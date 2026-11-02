package com.dersbekcisi.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val TR: Locale = Locale("tr", "TR")

/** Ayarlar cihaz şifresi girilmeden önce de okunabilsin diye cihaz-korumalı depolamada tutulur. */
object Prefs {
    @Volatile private var cached: SharedPreferences? = null

    fun sp(ctx: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val de = ctx.applicationContext.createDeviceProtectedStorageContext()
            val p = de.getSharedPreferences("bekci", Context.MODE_PRIVATE)
            cached = p
            return p
        }
    }

    fun dir(ctx: Context): File = ctx.applicationContext.createDeviceProtectedStorageContext().filesDir
}

data class Settings(
    val days: List<String>,
    val studyApps: Set<String>,
    val blockedApps: Set<String>,
    val keywords: List<String>,
    val checkSec: Int,
    val holdSec: Int,
    val graceSec: Int,
    val warnSec: Int,
    val offGraceSec: Int,
    val mathThreshold: Int,
    val minKeywords: Int,
    val personMemMin: Int,
    val personAlone: Boolean,
    val headphonePct: Int,
) {
    val keywordsNorm: List<String> by lazy {
        keywords.map { TextRules.norm(it).trim() }.filter { it.length >= 2 }.distinct()
    }
}

object Defaults {
    val DAYS = listOf("19:00-22:00", "19:00-22:00", "19:00-22:00", "19:00-22:00", "19:00-22:00", "", "")
    val STUDY = setOf("com.myscript.nebo")
    val BLOCKED = setOf(
        "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.twitter.android", "com.facebook.katana", "com.snapchat.android",
        "com.netflix.mediaclient", "com.discord", "com.reddit.frontpage", "tv.twitch.android.app",
    )
    val KEYWORDS = listOf(
        "TYT", "AYT", "YKS", "konu anlatımı", "soru çözümü", "deneme sınavı",
        "matematik", "geometri", "fizik", "kimya", "biyoloji",
        "türev", "integral", "limit", "logaritma", "fonksiyon", "polinom", "trigonometri",
        "olasılık", "permütasyon", "kombinasyon", "denklem", "eşitsizlik", "parabol",
        "vektör", "momentum", "hücre",
        "theorem", "derivative", "calculus", "lemma", "corollary",
    )
}

object Store {
    @Volatile private var cache: Pair<Long, Settings>? = null

    fun version(ctx: Context): Long = Prefs.sp(ctx).getLong("cfgVer", 0L)

    fun load(ctx: Context): Settings {
        val sp = Prefs.sp(ctx)
        val v = sp.getLong("cfgVer", 0L)
        cache?.let { if (it.first == v) return it.second }
        val s = Settings(
            days = (0 until 7).map { sp.getString("day$it", null) ?: Defaults.DAYS[it] },
            studyApps = sp.getStringSet("studyApps", null)?.toSet() ?: Defaults.STUDY,
            blockedApps = sp.getStringSet("blockedApps", null)?.toSet() ?: Defaults.BLOCKED,
            keywords = sp.getString("keywords", null)?.let { splitList(it) } ?: Defaults.KEYWORDS,
            checkSec = sp.getInt("checkSec", 20),
            holdSec = sp.getInt("holdSec", 40),
            graceSec = sp.getInt("graceSec", 45),
            warnSec = sp.getInt("warnSec", 30),
            offGraceSec = sp.getInt("offGraceSec", 10),
            mathThreshold = sp.getInt("mathThr", 3),
            minKeywords = sp.getInt("minKw", 1),
            personMemMin = sp.getInt("personMem", 10),
            personAlone = sp.getBoolean("personAlone", false),
            headphonePct = sp.getInt("hpPct", 30),
        )
        cache = v to s
        return s
    }

    fun save(ctx: Context, s: Settings) {
        val sp = Prefs.sp(ctx)
        val e = sp.edit()
        s.days.forEachIndexed { i, d -> e.putString("day$i", d.trim()) }
        e.putStringSet("studyApps", HashSet(s.studyApps))
        e.putStringSet("blockedApps", HashSet(s.blockedApps))
        e.putString("keywords", s.keywords.joinToString("\n"))
        e.putInt("checkSec", s.checkSec)
        e.putInt("holdSec", s.holdSec)
        e.putInt("graceSec", s.graceSec)
        e.putInt("warnSec", s.warnSec)
        e.putInt("offGraceSec", s.offGraceSec)
        e.putInt("mathThr", s.mathThreshold)
        e.putInt("minKw", s.minKeywords)
        e.putInt("personMem", s.personMemMin)
        e.putBoolean("personAlone", s.personAlone)
        e.putInt("hpPct", s.headphonePct)
        e.putLong("cfgVer", sp.getLong("cfgVer", 0L) + 1)
        e.commit()
    }

    fun splitList(s: String): List<String> =
        s.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Gün gün çalışma aralıkları. Gün 0 = Pazartesi ... 6 = Pazar. Gece yarısını geçen aralıklar desteklenir. */
class Schedule(days: List<String>) {
    private val ranges: Array<List<Pair<Int, Int>>> = Array(7) { i -> parseDay(days.getOrElse(i) { "" }) }

    companion object {
        private val RANGE = Regex("""(\d{1,2})[:.](\d{2})\s*[-–—]\s*(\d{1,2})[:.](\d{2})""")
        private val TOKEN = Regex("""^\s*\d{1,2}[:.]\d{2}\s*[-–—]\s*\d{1,2}[:.]\d{2}\s*$""")
        val DAY_NAMES = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")
        val DAY_SHORT = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")

        fun parseDay(s: String): List<Pair<Int, Int>> = RANGE.findAll(s).mapNotNull { m ->
            val h1 = m.groupValues[1].toInt()
            val m1 = m.groupValues[2].toInt()
            val h2 = m.groupValues[3].toInt()
            val m2 = m.groupValues[4].toInt()
            if (h1 > 24 || h2 > 24 || m1 > 59 || m2 > 59) null
            else {
                val a = (h1 * 60 + m1).coerceAtMost(1440)
                val b = (h2 * 60 + m2).coerceAtMost(1440)
                if (a == b) null else a to b
            }
        }.toList()

        fun valid(s: String): Boolean {
            val parts = s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return true
            return parts.all { TOKEN.matches(it) && parseDay(it).size == 1 }
        }

        fun dayIndex(cal: Calendar): Int = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }

    fun isEmpty(): Boolean = ranges.all { it.isEmpty() }

    fun isActive(timeMs: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMs
        return activeAt(dayIndex(cal), cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
    }

    private fun activeAt(d: Int, m: Int): Boolean {
        for ((a, b) in ranges[d]) {
            if (a < b) {
                if (m in a until b) return true
            } else if (m >= a) return true
        }
        val y = (d + 6) % 7
        for ((a, b) in ranges[y]) if (a > b && m < b) return true
        return false
    }

    /** Durumun (ders saati içi/dışı) değişeceği bir sonraki dakikanın zamanı. */
    fun nextChange(fromMs: Long): Long? {
        val cal = Calendar.getInstance()
        cal.timeInMillis = fromMs
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val cur = isActive(fromMs)
        var d = dayIndex(cal)
        var m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        for (step in 1..(8 * 1440)) {
            m++
            if (m >= 1440) { m = 0; d = (d + 1) % 7 }
            if (activeAt(d, m) != cur) {
                cal.add(Calendar.MINUTE, step)
                return cal.timeInMillis
            }
        }
        return null
    }

    /** Bugün, şu andan sonra başka ders saati var mı? */
    fun hasMoreToday(now: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val d = dayIndex(cal)
        var m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) + 1
        while (m < 1440) {
            if (activeAt(d, m)) return true
            m++
        }
        return false
    }

    fun describeNext(now: Long): String {
        val t = nextChange(now) ?: return "ders saati tanımlı değil"
        val cal = Calendar.getInstance()
        cal.timeInMillis = t
        val hm = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        return DAY_SHORT[dayIndex(cal)] + " " + hm
    }
}

object TextRules {
    fun norm(s: String): String {
        val lower = s.lowercase(TR)
        val sb = StringBuilder(lower.length)
        for (c in lower) {
            if (c == '̇') continue
            sb.append(
                when (c) {
                    'ç' -> 'c'
                    'ğ' -> 'g'
                    'ı' -> 'i'
                    'ö' -> 'o'
                    'ş' -> 's'
                    'ü' -> 'u'
                    'â' -> 'a'
                    'î' -> 'i'
                    'û' -> 'u'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /** Anahtar kelimeyi kelime başında arar; Türkçe ekler serbest ("türevi", "integrali"). */
    fun findKeywords(textNorm: String, keywordsNorm: List<String>): Set<String> {
        val hits = LinkedHashSet<String>()
        for (k in keywordsNorm) {
            var idx = textNorm.indexOf(k)
            while (idx >= 0) {
                val before = if (idx == 0) ' ' else textNorm[idx - 1]
                if (!before.isLetterOrDigit()) {
                    hits.add(k)
                    break
                }
                idx = textNorm.indexOf(k, idx + 1)
            }
        }
        return hits
    }

    private val MATH = listOf(
        Regex("""\d\s*[+×÷*/=^]\s*\(?\s*\d"""),
        Regex("""\b[xyzn]\s*[=<>≤≥]\s*[-+(]?\s*\d"""),
        Regex("""\b(sin|cos|tan|cot|log|ln|lim|det)\s*[(\dxyzθπ]"""),
        Regex("""\b[fgh]\s*\(\s*[xyzt]\s*\)"""),
        Regex("""\b\d*\s*[xyzn]\s*\^\s*\d"""),
        Regex("""\b\d+[xyz]\b"""),
        Regex("""[xyzn][²³]"""),
    )
    private const val SYMBOLS = "√∫∑∏∞≤≥≠≈πθΔ∆∂"

    /** Ekrandaki matematik ifadelerine puan verir; saat, yüzde gibi sıradan sayılar puan almaz. */
    fun mathScore(raw: String): Int {
        if (raw.isEmpty()) return 0
        val t = raw.lowercase(Locale.ROOT)
        var score = 0
        for (p in MATH) score += p.findAll(t).take(10).count()
        for (ch in SYMBOLS) if (t.indexOf(ch) >= 0) score++
        return score
    }
}

/** Uygulama içi günlük (tanı raporu için). */
object Journal {
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun add(ctx: Context, msg: String) {
        val line = synchronized(fmt) { fmt.format(Date()) } + " " + msg
        android.util.Log.i("DersBekcisi", line)
        synchronized(this) {
            try {
                val f = File(Prefs.dir(ctx), "journal.txt")
                f.appendText(line + "\n")
                if (f.length() > 120_000) f.writeText(f.readText().takeLast(60_000))
            } catch (_: Exception) {
            }
        }
    }

    fun tail(ctx: Context, n: Int): List<String> = synchronized(this) {
        try {
            File(Prefs.dir(ctx), "journal.txt").readLines().takeLast(n)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

object Stats {
    class Day(
        var studyMs: Long = 0, var violMs: Long = 0, var alarms: Int = 0, var warnings: Int = 0,
        var tamper: Int = 0, var unlocks: Int = 0, var reboots: Int = 0,
    ) {
        fun isEmpty() = studyMs == 0L && violMs == 0L && alarms == 0 && warnings == 0 && tamper == 0 && unlocks == 0 && reboots == 0
    }

    fun dayKey(t: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    fun load(ctx: Context, t: Long): Day {
        val s = Prefs.sp(ctx).getString("st_" + dayKey(t), null) ?: return Day()
        val p = s.split(',')
        fun l(i: Int) = p.getOrNull(i)?.toLongOrNull() ?: 0L
        return Day(l(0), l(1), l(2).toInt(), l(3).toInt(), l(4).toInt(), l(5).toInt(), l(6).toInt())
    }

    @Synchronized
    fun bump(ctx: Context, t: Long, f: (Day) -> Unit) {
        val d = load(ctx, t)
        f(d)
        Prefs.sp(ctx).edit().putString(
            "st_" + dayKey(t),
            listOf(d.studyMs, d.violMs, d.alarms, d.warnings, d.tamper, d.unlocks, d.reboots).joinToString(",")
        ).apply()
    }

    fun summary(d: Day): String {
        val sb = StringBuilder("Çalışma: ${Util.fmtDur(d.studyMs)} · Alarm: ${d.alarms} · Uyarı: ${d.warnings}")
        if (d.violMs > 0) sb.append(" · Ders dışı: ${Util.fmtDur(d.violMs)}")
        if (d.tamper > 0) sb.append(" · Kapatma denemesi: ${d.tamper}")
        if (d.unlocks > 0) sb.append(" · Kilit açma: ${d.unlocks}")
        if (d.reboots > 0) sb.append(" · Ders saatinde yeniden başlatma: ${d.reboots}")
        return sb.toString()
    }

    fun recent(ctx: Context, n: Int): List<Pair<String, Day>> {
        val out = ArrayList<Pair<String, Day>>()
        val cal = Calendar.getInstance()
        val f = SimpleDateFormat("dd.MM", Locale.US)
        for (i in 0 until n) {
            val d = load(ctx, cal.timeInMillis)
            if (i == 0 || !d.isEmpty()) {
                out.add((f.format(cal.time) + " " + Schedule.DAY_SHORT[Schedule.dayIndex(cal)]) to d)
            }
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return out
    }
}

enum class PinResult { OK, WRONG, LOCKED, NONE }

/** Koruma: kurulu mu, bakım modunda mı, PIN ve 10 dakikalık bekleme. */
object Control {
    const val WAIT_MS = 10 * 60_000L
    const val MAINT_MS = 15 * 60_000L
    const val REQ_VALID_MS = 40 * 60_000L
    private const val PIN_LOCK_MS = 10 * 60_000L

    private fun sp(ctx: Context) = Prefs.sp(ctx)

    fun armed(ctx: Context) = sp(ctx).getBoolean("armed", false)
    fun maintUntil(ctx: Context) = sp(ctx).getLong("maintUntil", 0L)
    fun inMaint(ctx: Context, now: Long) = now < maintUntil(ctx)
    fun protectionActive(ctx: Context, now: Long) = armed(ctx) && !inMaint(ctx, now)
    fun enforcementActive(ctx: Context, now: Long, sch: Schedule) = protectionActive(ctx, now) && sch.isActive(now)
    fun editable(ctx: Context, now: Long) = !armed(ctx) || inMaint(ctx, now)

    fun arm(ctx: Context) {
        sp(ctx).edit().putBoolean("armed", true).putLong("maintUntil", 0L).putLong("unlockReq", 0L).commit()
        Journal.add(ctx, "Koruma başlatıldı")
        Scheduler.reschedule(ctx)
    }

    fun disarm(ctx: Context) {
        sp(ctx).edit().putBoolean("armed", false).putLong("maintUntil", 0L).putLong("unlockReq", 0L).commit()
        Stats.bump(ctx, System.currentTimeMillis()) { it.unlocks++ }
        Journal.add(ctx, "Koruma tamamen kapatıldı")
        Scheduler.reschedule(ctx)
    }

    fun unlockReq(ctx: Context) = sp(ctx).getLong("unlockReq", 0L)
    fun requestValid(ctx: Context, now: Long): Boolean {
        val r = unlockReq(ctx)
        return r != 0L && now - r <= REQ_VALID_MS
    }
    fun unlockReadyAt(ctx: Context) = unlockReq(ctx) + WAIT_MS
    fun requestExpiresAt(ctx: Context) = unlockReq(ctx) + REQ_VALID_MS

    fun requestUnlock(ctx: Context, now: Long) {
        if (!requestValid(ctx, now)) {
            sp(ctx).edit().putLong("unlockReq", now).commit()
            Journal.add(ctx, "10 dakikalık kilit açma beklemesi başladı")
        }
    }

    fun startMaint(ctx: Context, now: Long, via: String) {
        sp(ctx).edit().putLong("maintUntil", now + MAINT_MS).putLong("unlockReq", 0L).commit()
        Stats.bump(ctx, now) { it.unlocks++ }
        Journal.add(ctx, "Kilit açıldı ($via): 15 dk bakım modu")
        Scheduler.reschedule(ctx)
    }

    fun endMaint(ctx: Context) {
        sp(ctx).edit().putLong("maintUntil", 0L).commit()
        Journal.add(ctx, "Bakım modu bitti, koruma açık")
        Scheduler.reschedule(ctx)
    }

    fun hasPin(ctx: Context) = sp(ctx).getString("pinHash", null) != null

    fun setPin(ctx: Context, pin: String) {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = bytes.joinToString("") { "%02x".format(it) }
        sp(ctx).edit().putString("pinSalt", salt).putString("pinHash", hash(salt + pin)).putInt("pinFails", 0).commit()
        Journal.add(ctx, "Acil durum PIN'i belirlendi")
    }

    fun pinLockedUntil(ctx: Context) = sp(ctx).getLong("pinLockUntil", 0L)

    fun tryPin(ctx: Context, pin: String, now: Long): PinResult {
        val p = sp(ctx)
        val h = p.getString("pinHash", null) ?: return PinResult.NONE
        if (now < p.getLong("pinLockUntil", 0L)) return PinResult.LOCKED
        if (hash((p.getString("pinSalt", "") ?: "") + pin) == h) {
            p.edit().putInt("pinFails", 0).commit()
            return PinResult.OK
        }
        val fails = p.getInt("pinFails", 0) + 1
        val e = p.edit().putInt("pinFails", fails)
        if (fails >= 5) e.putInt("pinFails", 0).putLong("pinLockUntil", now + PIN_LOCK_MS)
        e.commit()
        Journal.add(ctx, "Yanlış PIN denemesi ($fails)")
        return if (fails >= 5) PinResult.LOCKED else PinResult.WRONG
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

object Audio {
    fun isHeadphone(t: Int): Boolean =
        t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_HEARING_AID ||
            t == 26 /* TYPE_BLE_HEADSET */

    fun headphonesConnected(am: AudioManager): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isHeadphone(it.type) }

    fun typeName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "tablet hoparlörü"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "kablolu kulaklık"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB kulaklık"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26 -> "Bluetooth kulaklık"
        else -> "cihaz $t"
    }
}

object Util {
    fun a11yEnabled(ctx: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(ctx, BekciService::class.java)
        val full = cn.flattenToString()
        val short = cn.flattenToShortString()
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    fun imePackage(ctx: Context): String? =
        android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun label(ctx: Context, pkg: String?): String {
        if (pkg == null) return "bilinmiyor"
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    fun launchableApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(TR) }
    }

    fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    fun fmtDur(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) "${min / 60} sa ${min % 60} dk" else "$min dk"
    }

    fun mmss(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
