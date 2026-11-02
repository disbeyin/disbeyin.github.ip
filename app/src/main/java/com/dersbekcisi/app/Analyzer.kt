package com.dersbekcisi.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** Ekran durumunun anlık görüntüsü (ana iş parçacığında toplanır). */
class Probe(
    val focused: String?,
    val visible: List<String>,
    val pip: List<String>,
    val treeText: String,
    val mediaActive: Boolean,
    val bannerPx: Int,
    val why: String,
)

class ShotResult(val ok: Boolean, val text: String, val faces: Int, val error: String?)

/**
 * Ekran görüntüsünü alır, cihaz içindeki ML Kit ile yazı tanır (OCR) ve gerekirse yüz arar.
 * Görüntü hiçbir yere kaydedilmez veya gönderilmez; iş bitince bellekten silinir.
 */
class Analyzer(private val svc: AccessibilityService, private val worker: ExecutorService) {
    private val main = Handler(Looper.getMainLooper())
    private var textRec: TextRecognizer? = null
    private var faceDet: FaceDetector? = null

    fun close() {
        try { textRec?.close() } catch (_: Exception) {}
        try { faceDet?.close() } catch (_: Exception) {}
    }

    fun screenshot(bannerPx: Int, needFaces: Boolean, done: (ShotResult) -> Unit) {
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val r = try {
                        process(screenshot, bannerPx, needFaces)
                    } catch (t: Throwable) {
                        ShotResult(false, "", 0, "işleme hatası: ${t.javaClass.simpleName} ${t.message ?: ""}")
                    }
                    main.post { done(r) }
                }

                override fun onFailure(errorCode: Int) {
                    main.post { done(ShotResult(false, "", 0, "ekran görüntüsü alınamadı (kod $errorCode)")) }
                }
            })
        } catch (t: Throwable) {
            main.post { done(ShotResult(false, "", 0, "ekran görüntüsü: ${t.javaClass.simpleName} ${t.message ?: ""}")) }
        }
    }

    private fun process(r: AccessibilityService.ScreenshotResult, bannerPx: Int, needFaces: Boolean): ShotResult {
        val hb = r.hardwareBuffer
        val full: Bitmap
        try {
            val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace) ?: return ShotResult(false, "", 0, "görüntü çözülemedi")
            full = hw.copy(Bitmap.Config.ARGB_8888, false) ?: return ShotResult(false, "", 0, "görüntü kopyalanamadı")
            hw.recycle()
        } finally {
            hb.close()
        }
        try {
            // Yazı tanıma: büyük tablet ekranını makul boyuta indir.
            val scale = min(1f, 1600f / max(full.width, full.height))
            val ocrBmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(full, max(1, (full.width * scale).toInt()), max(1, (full.height * scale).toInt()), true)
            } else full
            val cutTop = (bannerPx * scale).toInt()
            val tr = textRec ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { textRec = it }
            val text = Tasks.await(tr.process(InputImage.fromBitmap(ocrBmp, 0)), 10, TimeUnit.SECONDS)
            val sb = StringBuilder()
            if (text != null) {
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox
                        // Bekçinin kendi uyarı şeridindeki yazıyı sayma.
                        if (cutTop > 0 && box != null && box.bottom <= cutTop + 4) continue
                        sb.append(line.text).append('\n')
                    }
                }
            }
            if (ocrBmp !== full) ocrBmp.recycle()

            var faces = 0
            if (needFaces) {
                val fs = min(1f, 900f / max(full.width, full.height))
                val fb = if (fs < 1f) {
                    Bitmap.createScaledBitmap(full, max(1, (full.width * fs).toInt()), max(1, (full.height * fs).toInt()), true)
                } else full
                val fd = faceDet ?: FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setMinFaceSize(0.05f)
                        .build()
                ).also { faceDet = it }
                val list = Tasks.await(fd.process(InputImage.fromBitmap(fb, 0)), 6, TimeUnit.SECONDS)
                faces = list?.size ?: 0
                if (fb !== full) fb.recycle()
            }
            return ShotResult(true, sb.toString(), faces, null)
        } finally {
            full.recycle()
        }
    }
}
