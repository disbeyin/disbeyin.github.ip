package com.dersbekcisi.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var setupBox: LinearLayout
    private lateinit var liveText: TextView
    private lateinit var protectInfo: TextView
    private lateinit var protectBox: LinearLayout
    private lateinit var formBox: LinearLayout
    private lateinit var reportText: TextView
    private lateinit var kwEdit: EditText
    private lateinit var personSw: Switch
    private lateinit var studyText: TextView
    private lateinit var blockedText: TextView
    private val dayEdits = ArrayList<EditText>()
    private val nums = LinkedHashMap<String, EditText>()
    private var studySel = HashSet<String>()
    private var blockedSel = HashSet<String>()
    private var protectSig = ""
    private var formEnabled: Boolean? = null

    private val ticker = object : Runnable {
        override fun run() {
            try {
                refreshLive()
            } catch (_: Throwable) {
            }
            h.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- kurulum

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notif.ensure(this)
        val scroll = ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(22), dp(18), dp(48))
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("Ders Bekçisi", 26f, bold = true))
        root.addView(small("Sürüm ${Util.versionName(this)}. Ekran yalnızca bu tablette incelenir; görüntüler kaydedilmez, hiçbir yere gönderilmez."))

        root.addView(header("1. Kurulum"))
        setupBox = vbox()
        root.addView(setupBox)

        root.addView(header("2. Şu an"))
        liveText = text("")
        root.addView(liveText)
        root.addView(button("Alarm testi (3 sn)") { alarmTest() })
        root.addView(button("5 sn sonra ekranı analiz et") { testAnalysis() })

        root.addView(header("3. Bekçiyi başlat ve kilitle"))
        protectInfo = text("")
        root.addView(protectInfo)
        protectBox = vbox()
        root.addView(protectBox)

        root.addView(header("4. Ayarlar"))
        formBox = vbox()
        root.addView(formBox)
        buildForm()
        loadForm()

        root.addView(header("5. Rapor"))
        reportText = text("")
        root.addView(reportText)

        root.addView(header("6. Sorun olursa"))
        root.addView(small("Bir şey ters giderse bu raporu kopyalayıp Claude'a yapıştır. Ekran içeriği ya da kişisel bilgi içermez."))
        root.addView(button("Tanı raporunu kopyala") { copyDiag() })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        refreshProtect(true)
        refreshReport()
        h.removeCallbacks(ticker)
        h.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(ticker)
    }

    // ---------------------------------------------------------------- 1. kurulum listesi

    private fun refreshSetup() {
        setupBox.removeAllViews()
        val a11y = Util.a11yEnabled(this)
        val running = BekciService.instance != null
        setupItem(
            a11y && running, "Erişilebilirlik hizmeti (zorunlu)",
            if (a11y && !running) "Açık görünüyor ama çalışmıyor: kapatıp yeniden aç."
            else "Erişilebilirlik → Yüklü uygulamalar → Ders Bekçisi → Aç. Seçenek gri ya da kilitliyse önce aşağıdaki 'Uygulama bilgisi' düğmesine bas, sağ üstteki ⋮ menüsünden 'Kısıtlanmış ayarlara izin ver'i seç, sonra geri dön.",
            "Erişilebilirliği aç"
        ) { open(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (!a11y) {
            setupBox.addView(button("Uygulama bilgisi (kısıtlanmış ayar izni)") {
                open(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }
        val nm = getSystemService(NotificationManager::class.java)
        setupItem(nm?.areNotificationsEnabled() == true, "Bildirim izni", "Uyarı ve günlük rapor bildirimleri için.", "Bildirim ayarları") {
            open(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        }
        val pm = getSystemService(PowerManager::class.java)
        setupItem(pm?.isIgnoringBatteryOptimizations(packageName) == true, "Pil optimizasyonundan muaf", "Ekran kapalıyken alarmın kesilmemesi için.", "Muaf tut") {
            open(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val adminCn = ComponentName(this, AdminReceiver::class.java)
        setupItem(dpm?.isAdminActive(adminCn) == true, "Silmeye karşı koruma (önerilir)", "Etkinken uygulamayı silmek için önce bu korumanın kapatılması gerekir.", "Etkinleştir") {
            open(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminCn)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Ders Bekçisi'nin kolayca silinmesini zorlaştırır.")
            )
        }
        setupItem(nm?.isNotificationPolicyAccessGranted == true, "Rahatsız Etmeyin erişimi (isteğe bağlı)", "Rahatsız Etmeyin açıkken de alarmın duyulması için.", "Erişim ver") {
            open(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }

    private fun setupItem(ok: Boolean, title: String, hint: String, btn: String, action: () -> Unit) {
        val t = text((if (ok) "✓ " else "✗ ") + title, 16f, bold = true)
        t.setTextColor(if (ok) Color.rgb(46, 125, 50) else Color.rgb(198, 40, 40))
        setupBox.addView(t)
        if (!ok) {
            setupBox.addView(small(hint))
            setupBox.addView(button(btn, action))
        }
    }

    // ---------------------------------------------------------------- 2. şu an

    private fun refreshLive() {
        val now = System.currentTimeMillis()
        val sch = Schedule(Store.load(this).days)
        val sb = StringBuilder()
        sb.append(if (sch.isActive(now)) "Ders saati: EVET" else "Ders saati: hayır (sonraki değişim: ${sch.describeNext(now)})")
        sb.append('\n')
        val e = BekciService.instance
        if (e == null) sb.append("Bekçi hizmeti çalışmıyor. Kurulum listesine bak.") else sb.append(e.describe())
        liveText.text = sb.toString()
        refreshProtect(false)
    }

    private fun alarmTest() {
        AlarmPlayer.start(this, "test")
        h.postDelayed({ AlarmPlayer.stop(this, "test") }, 3000)
        val am = getSystemService(AudioManager::class.java)
        val hp = am != null && Audio.headphonesConnected(am)
        toast(
            if (hp) "Kulaklık takılı: ses %${Store.load(this).headphonePct} ile sınırlı, tablet hoparlörüne yönlendiriliyor."
            else "Son ses, 3 saniye."
        )
    }

    private fun testAnalysis() {
        val e = BekciService.instance
        if (e == null) {
            toast("Önce erişilebilirlik hizmetini aç.")
            return
        }
        e.requestTestAnalysis(5000)
        toast("5 saniye içinde incelemek istediğin ekrana geç. Sonuç bildirim olarak gelecek.")
    }

    // ---------------------------------------------------------------- 3. koruma

    private fun refreshProtect(force: Boolean) {
        val now = System.currentTimeMillis()
        val armed = Control.armed(this)
        val maint = Control.inMaint(this, now)
        val req = armed && !maint && Control.requestValid(this, now)
        val ready = req && now >= Control.unlockReadyAt(this)
        val trial = Control.trial(this)
        protectInfo.text = when {
            !armed && trial -> "DENEME MODU: ders saatinde izleme ve alarm çalışır ama hiçbir şey kilitli değil. Her şey yolundaysa kilitle."
            !armed -> "Bekçi KAPALI: ders saatinde izleme yapılmaz. Önce deneme modunda dene, sonra kilitle."
            maint -> "Bakım modu: ${Util.mmss(Control.maintUntil(this) - now)} kaldı. Bekçi duraklatıldı, ayarlar açık. Süre bitince koruma kendiliğinden geri gelir."
            ready -> "Bekleme bitti. ${Util.mmss(Control.requestExpiresAt(this) - now)} içinde bakımı başlatabilirsin."
            req -> "Kilit açma isteği: ${Util.mmss(Control.unlockReadyAt(this) - now)} sonra açılabilecek. Bu sürede bekçi çalışmaya devam eder."
            else -> "Koruma AÇIK. Ayarları değiştirmek ya da bekçiyi kapatmak için kilidi aç."
        }
        val sig = "$armed/$trial/$maint/$req/$ready/${Control.hasPin(this)}"
        if (force || sig != protectSig) {
            protectSig = sig
            protectBox.removeAllViews()
            if (!armed) {
                protectBox.addView(button(if (trial) "Deneme modunu durdur" else "Deneme modunda başlat (kilitsiz)") {
                    Control.setTrial(this, !trial)
                    stateChanged()
                })
                protectBox.addView(button("Bekçiyi başlat ve kilitle") {
                    confirm(
                        "Bekçiyi kilitle?",
                        "Bundan sonra ayarları değiştirmek ya da bekçiyi kapatmak için ya acil durum PIN'i ya da 10 dakika bekleme gerekecek. Ayarlardaki bekçiyle ilgili sayfalar (uygulama bilgisi, erişilebilirlik, cihaz yöneticisi) da kapatılacak."
                    ) {
                        Control.arm(this)
                        stateChanged()
                    }
                })
            } else if (maint) {
                protectBox.addView(button("Bakımı bitir (korumayı geri aç)") {
                    Control.endMaint(this)
                    stateChanged()
                })
                protectBox.addView(button("Korumayı tamamen kapat") {
                    confirm("Korumayı kapat?", "Bekçi tamamen devre dışı kalır. Bu işlem rapora yazılır.") {
                        Control.disarm(this)
                        stateChanged()
                    }
                })
            } else {
                if (Control.hasPin(this)) protectBox.addView(button("PIN ile hemen aç (acil durum)") { pinUnlock() })
                if (!req) {
                    protectBox.addView(button("10 dakika bekleyerek aç") {
                        Control.requestUnlock(this, System.currentTimeMillis())
                        stateChanged()
                    })
                }
                if (ready) {
                    protectBox.addView(button("Bakımı başlat (15 dk)") {
                        Control.startMaint(this, System.currentTimeMillis(), "10 dk bekleme")
                        stateChanged()
                    })
                }
            }
            if (!armed || maint) {
                protectBox.addView(button(if (Control.hasPin(this)) "PIN'i değiştir" else "Acil durum PIN'i belirle (başkası girsin)") { pinSetup() })
            }
        }
        setFormEnabled(Control.editable(this, now))
    }

    private fun stateChanged() {
        refreshProtect(true)
        BekciService.instance?.onExternalTick()
    }

    private fun pinUnlock() {
        askPin("Acil durum PIN'i") { pin ->
            val now = System.currentTimeMillis()
            when (Control.tryPin(this, pin, now)) {
                PinResult.OK -> {
                    Control.startMaint(this, now, "PIN")
                    toast("Kilit açıldı: 15 dakika bakım modu.")
                    stateChanged()
                }
                PinResult.LOCKED -> toast("Çok fazla yanlış deneme. ${Util.mmss(Control.pinLockedUntil(this) - now)} sonra tekrar dene.")
                PinResult.NONE -> toast("PIN belirlenmemiş.")
                PinResult.WRONG -> toast("Yanlış PIN.")
            }
        }
    }

    private fun pinSetup() {
        val setNew = {
            askPin("Yeni PIN (4-8 rakam)") { p1 ->
                if (p1.length !in 4..8 || !p1.all { it.isDigit() }) {
                    toast("PIN 4-8 rakam olmalı.")
                } else {
                    askPin("Yeni PIN'i tekrar gir") { p2 ->
                        if (p1 != p2) {
                            toast("PIN'ler uyuşmadı.")
                        } else {
                            Control.setPin(this, p1)
                            toast("PIN kaydedildi. PIN'i bilen kişi acil durumda bekçiyi hemen açabilir.")
                            stateChanged()
                        }
                    }
                }
            }
        }
        if (Control.hasPin(this)) {
            askPin("Mevcut PIN") { old ->
                if (Control.tryPin(this, old, System.currentTimeMillis()) == PinResult.OK) setNew() else toast("Mevcut PIN yanlış.")
            }
        } else {
            setNew()
        }
    }

    private fun askPin(title: String, onPin: (String) -> Unit) {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        et.hint = "PIN"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("Tamam") { _, _ -> onPin(et.text.toString().trim()) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---------------------------------------------------------------- 4. ayarlar

    private fun buildForm() {
        formBox.addView(small("Gün gün çalışma saatleri. Örnek: 19:00-22:00 ya da 09:00-12:00, 14:00-17:00. Boş bırakılan gün ders yok demektir."))
        for (i in 0 until 7) {
            val et = EditText(this)
            et.setSingleLine()
            et.hint = "boş"
            dayEdits.add(et)
            formBox.addView(labeled(Schedule.DAY_NAMES[i], et))
        }

        formBox.addView(small("Ders uygulamaları: önde açıkken her zaman ders sayılır (ör. Nebo, PDF okuyucun). Kâğıtta soru çözerken bunlardan biri açık kalsın."))
        studyText = text("")
        formBox.addView(studyText)
        formBox.addView(button("Ders uygulamalarını seç") { pickApps(true) })

        formBox.addView(small("Yasaklı uygulamalar: ekranda ders kelimesi olsa bile asla ders sayılmaz."))
        blockedText = text("")
        formBox.addView(blockedText)
        formBox.addView(button("Yasaklı uygulamaları seç") { pickApps(false) })

        formBox.addView(small("Anahtar kelimeler (her satıra bir tane). Büyük-küçük harf ve Türkçe karakter fark etmez; kelimenin sonuna ek gelebilir (türev → türevi)."))
        kwEdit = EditText(this)
        kwEdit.minLines = 5
        kwEdit.gravity = Gravity.TOP or Gravity.START
        formBox.addView(kwEdit)

        addNum("checkSec", "Kontrol aralığı (sn)")
        addNum("holdSec", "Ders kanıtı ne kadar geçerli (sn)")
        addNum("graceSec", "Kanıt kaybolunca tolerans (sn)")
        addNum("warnSec", "Uyarı süresi (sn)")
        addNum("offGraceSec", "Ekran kapanınca tolerans (sn)")
        addNum("mathThr", "Matematik ifadesi eşiği (puan)")
        addNum("minKw", "Gereken en az kelime sayısı")
        addNum("personMem", "Anlatan kişi için ders içeriği hafızası (dk)")
        addNum("hpPct", "Kulaklık takılıyken alarm sesi (%)")

        personSw = Switch(this)
        personSw.text = "Anlatan kişi (yüz + ses) tek başına ders sayılsın"
        formBox.addView(personSw)

        formBox.addView(button("Ayarları kaydet") { save() })
    }

    private fun addNum(key: String, label: String) {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setSingleLine()
        nums[key] = et
        formBox.addView(labeled(label, et))
    }

    private fun loadForm() {
        val s = Store.load(this)
        for (i in 0 until 7) dayEdits[i].setText(s.days[i])
        studySel = HashSet(s.studyApps)
        blockedSel = HashSet(s.blockedApps)
        kwEdit.setText(s.keywords.joinToString("\n"))
        nums["checkSec"]?.setText(s.checkSec.toString())
        nums["holdSec"]?.setText(s.holdSec.toString())
        nums["graceSec"]?.setText(s.graceSec.toString())
        nums["warnSec"]?.setText(s.warnSec.toString())
        nums["offGraceSec"]?.setText(s.offGraceSec.toString())
        nums["mathThr"]?.setText(s.mathThreshold.toString())
        nums["minKw"]?.setText(s.minKeywords.toString())
        nums["personMem"]?.setText(s.personMemMin.toString())
        nums["hpPct"]?.setText(s.headphonePct.toString())
        personSw.isChecked = s.personAlone
        updateAppTexts()
    }

    private fun save() {
        if (!Control.editable(this, System.currentTimeMillis())) {
            toast("Koruma açık: önce kilidi aç.")
            return
        }
        val days = dayEdits.map { it.text.toString().trim() }
        for (i in days.indices) {
            if (days[i].isNotEmpty() && !Schedule.valid(days[i])) {
                toast("${Schedule.DAY_NAMES[i]}: saat biçimi hatalı. Örnek: 19:00-22:00")
                return
            }
        }
        val old = Store.load(this)
        fun num(k: String, lo: Int, hi: Int, def: Int): Int =
            (nums[k]?.text?.toString()?.trim()?.toIntOrNull() ?: def).coerceIn(lo, hi)
        val kws = Store.splitList(kwEdit.text.toString())
        val s = Settings(
            days = days,
            studyApps = HashSet(studySel),
            blockedApps = HashSet(blockedSel),
            keywords = if (kws.isEmpty()) Defaults.KEYWORDS else kws,
            checkSec = num("checkSec", 5, 120, old.checkSec),
            holdSec = num("holdSec", 10, 600, old.holdSec),
            graceSec = num("graceSec", 0, 600, old.graceSec),
            warnSec = num("warnSec", 5, 300, old.warnSec),
            offGraceSec = num("offGraceSec", 0, 120, old.offGraceSec),
            mathThreshold = num("mathThr", 1, 30, old.mathThreshold),
            minKeywords = num("minKw", 1, 10, old.minKeywords),
            personMemMin = num("personMem", 1, 180, old.personMemMin),
            personAlone = personSw.isChecked,
            headphonePct = num("hpPct", 5, 100, old.headphonePct),
        )
        Store.save(this, s)
        Scheduler.reschedule(this)
        Journal.add(this, "Ayarlar kaydedildi")
        BekciService.instance?.onExternalTick()
        toast("Kaydedildi.")
        loadForm()
    }

    private fun pickApps(study: Boolean) {
        if (!Control.editable(this, System.currentTimeMillis())) {
            toast("Koruma açık: önce kilidi aç.")
            return
        }
        val apps = Util.launchableApps(this).filter { it.second != packageName }
        val sel = if (study) studySel else blockedSel
        val labels = apps.map { it.first }.toTypedArray<CharSequence>()
        val checked = BooleanArray(apps.size) { apps[it].second in sel }
        AlertDialog.Builder(this)
            .setTitle(if (study) "Ders uygulamaları" else "Yasaklı uygulamalar")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Tamam") { _, _ ->
                val installed = apps.map { it.second }.toSet()
                val keep = HashSet(sel.filter { it !in installed })
                apps.forEachIndexed { i, p -> if (checked[i]) keep.add(p.second) }
                if (study) {
                    studySel = keep
                    blockedSel.removeAll(keep)
                } else {
                    blockedSel = keep
                    studySel.removeAll(keep)
                }
                updateAppTexts()
                toast("Seçildi. 'Ayarları kaydet'e basmayı unutma.")
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun updateAppTexts() {
        fun names(set: Set<String>): String {
            val l = set.filter { Util.isInstalled(this, it) }.map { Util.label(this, it) }.sortedBy { it.lowercase(TR) }
            return if (l.isEmpty()) "(yüklü olan seçili yok)" else l.joinToString(", ")
        }
        studyText.text = names(studySel)
        blockedText.text = names(blockedSel)
    }

    private fun setFormEnabled(on: Boolean) {
        if (formEnabled == on) return
        formEnabled = on
        setEnabledRec(formBox, on)
    }

    private fun setEnabledRec(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is ViewGroup) for (i in 0 until v.childCount) setEnabledRec(v.getChildAt(i), on)
    }

    // ---------------------------------------------------------------- 5. rapor, 6. tanı

    private fun refreshReport() {
        val rows = Stats.recent(this, 14)
        reportText.text = rows.joinToString("\n") { (label, d) -> label + " — " + Stats.summary(d) }
    }

    private fun copyDiag() {
        val cm = getSystemService(ClipboardManager::class.java)
        if (cm == null) {
            toast("Pano kullanılamıyor.")
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText("Ders Bekçisi tanı raporu", Diag.build(this)))
        toast("Tanı raporu kopyalandı.")
    }

    // ---------------------------------------------------------------- görünüm yardımcıları

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun vbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun text(s: String, size: Float = 15f, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        t.setPadding(0, dp(4), 0, dp(4))
        return t
    }

    private fun small(s: String): TextView {
        val t = text(s, 13f)
        t.alpha = 0.8f
        return t
    }

    private fun header(s: String): TextView {
        val t = text(s, 20f, bold = true)
        t.setPadding(0, dp(22), 0, dp(6))
        return t
    }

    private fun button(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { action() }
        return b
    }

    private fun labeled(label: String, field: View): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val t = text(label)
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        return row
    }

    private fun confirm(title: String, msg: String, yes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Evet") { _, _ -> yes() }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (_: Exception) {
            toast("Bu ayar ekranı açılamadı; Ayarlar'dan elle bul.")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
