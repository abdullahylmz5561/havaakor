package com.example.havaakor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

/** Normalize (0..1) edilmiş bir landmark noktası. Görüntü dik (portrait) ve aynalanmamış. */
data class Pt(val x: Float, val y: Float)

/** MediaPipe'ın verdiği 21 landmark. */
data class Hand(val pts: List<Pt>)

/**
 * CameraX karelerini MediaPipe Hand Landmarker'a verir,
 * sonuçları [onResult] ile (arka plan thread'inde) döndürür.
 *
 * Model dosyası app/src/main/assets/hand_landmarker.task olmalı (README'ye bak).
 */
class HandAnalyzer(
    context: Context,
    private val onResult: (List<Hand>) -> Unit,
) : ImageAnalysis.Analyzer {

    private val landmarker: HandLandmarker
    private var lastTimestamp = 0L

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setNumHands(2)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ ->
                onResult(result.landmarks().map { lm -> Hand(lm.map { Pt(it.x(), it.y()) }) })
            }
            .setErrorListener { /* kare atla */ }
            .build()
        landmarker = HandLandmarker.createFromOptions(context, options)
    }

    override fun analyze(image: ImageProxy) {
        try {
            val bitmap = image.toBitmap()
            val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            // Zaman damgası kesinlikle artan olmalı
            val ts = maxOf(SystemClock.uptimeMillis(), lastTimestamp + 1)
            lastTimestamp = ts
            landmarker.detectAsync(BitmapImageBuilder(upright).build(), ts)
        } finally {
            image.close()
        }
    }

    fun close() {
        landmarker.close()
    }
}
