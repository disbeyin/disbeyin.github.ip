package com.dersbekcisi.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Ekranın üstünde uyarı/alarm şeridi ve ders saatinde ekranı açık tutan görünmez katman. */
class Overlay(private val svc: AccessibilityService) {
    private val wm: WindowManager? = svc.getSystemService(WindowManager::class.java)
    private var keepView: View? = null
    private var banner: LinearLayout? = null
    private var titleView: TextView? = null
    private var subView: TextView? = null
    var bannerHeight = 0
        private set
    var onBannerClick: (() -> Unit)? = null

    fun setKeepAwake(on: Boolean) {
        val w = wm ?: return
        if (on) {
            if (keepView != null) return
            val v = View(svc)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            try {
                w.addView(v, lp)
                keepView = v
            } catch (e: Exception) {
                Journal.add(svc, "Ekranı açık tutma katmanı eklenemedi: $e")
            }
        } else {
            keepView?.let { try { w.removeView(it) } catch (_: Exception) {} }
            keepView = null
        }
    }

    fun showBanner(alarm: Boolean, title: String, sub: String) {
        val w = wm ?: return
        if (banner == null) {
            val ll = LinearLayout(svc)
            ll.orientation = LinearLayout.VERTICAL
            ll.setPadding(dp(18), dp(14), dp(18), dp(14))
            val t1 = TextView(svc)
            t1.textSize = 21f
            t1.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            t1.setTextColor(Color.WHITE)
            val t2 = TextView(svc)
            t2.textSize = 15f
            t2.setTextColor(Color.WHITE)
            ll.addView(t1)
            ll.addView(t2)
            ll.setOnClickListener { onBannerClick?.invoke() }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP
            try {
                w.addView(ll, lp)
            } catch (e: Exception) {
                Journal.add(svc, "Uyarı şeridi gösterilemedi: $e")
                return
            }
            ll.viewTreeObserver.addOnGlobalLayoutListener { bannerHeight = ll.height }
            banner = ll
            titleView = t1
            subView = t2
        }
        banner?.setBackgroundColor(if (alarm) Color.rgb(198, 40, 40) else Color.rgb(230, 100, 0))
        titleView?.text = title
        subView?.text = "$sub\nDokun: ders uygulamasını aç"
    }

    fun hideBanner() {
        val w = wm
        banner?.let { b -> try { w?.removeView(b) } catch (_: Exception) {} }
        banner = null
        titleView = null
        subView = null
        bannerHeight = 0
    }

    fun removeAll() {
        hideBanner()
        setKeepAwake(false)
    }

    private fun dp(v: Int): Int = (v * svc.resources.displayMetrics.density).toInt()
}
