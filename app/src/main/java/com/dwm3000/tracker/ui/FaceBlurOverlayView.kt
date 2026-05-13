package com.dwm3000.tracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.dwm3000.tracker.vision.DetectedFace
import com.dwm3000.tracker.vision.FaceDetectionStats
import kotlin.math.max

class FaceBlurOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var frameBitmap: Bitmap? = null
    private var faces: List<DetectedFace> = emptyList()
    private var stats: FaceDetectionStats? = null
    private var statsTopInsetPx = 0f

    private val srcRect = Rect()
    private val tinyRect = Rect()
    private val imageRect = RectF()
    private val mappedRect = RectF()
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFD54F")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val landmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF00E5FF")
        style = Paint.Style.FILL
    }
    private val bitmapPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 23f
        setShadowLayer(5f, 0f, 2f, Color.BLACK)
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#77000000")
        style = Paint.Style.FILL
    }

    fun updateDetections(bitmap: Bitmap, detectedFaces: List<DetectedFace>, detectionStats: FaceDetectionStats) {
        val oldBitmap = frameBitmap
        frameBitmap = bitmap
        faces = detectedFaces
        stats = detectionStats
        if (oldBitmap !== bitmap && oldBitmap?.isRecycled == false) oldBitmap.recycle()
        invalidate()
    }

    fun clearDetections() {
        val oldBitmap = frameBitmap
        frameBitmap = null
        faces = emptyList()
        stats = null
        if (oldBitmap?.isRecycled == false) oldBitmap.recycle()
        invalidate()
    }

    fun setStatsTopInset(topInsetPx: Float) {
        if (statsTopInsetPx == topInsetPx) return
        statsTopInsetPx = topInsetPx
        invalidate()
    }

    override fun onDetachedFromWindow() {
        clearDetections()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = frameBitmap ?: return
        if (bitmap.isRecycled || width <= 0 || height <= 0) return

        canvas.drawColor(Color.BLACK)
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        mapImageToViewRect(bitmap.width, bitmap.height, imageRect)
        canvas.drawBitmap(bitmap, srcRect, imageRect, framePaint)

        var drewFacePatch = false
        faces.forEach { face ->
            val expanded = face.bbox.expand(0.22f, 0.32f, bitmap.width, bitmap.height)
            expanded.roundOut(srcRect)
            if (srcRect.width() <= 1 || srcRect.height() <= 1) return@forEach

            mapImageRectToView(expanded, bitmap.width, bitmap.height, mappedRect)
            drawPixelatedFace(canvas, bitmap, srcRect, mappedRect)
            drewFacePatch = true
            canvas.drawRect(mappedRect, boxPaint)
            drawLandmarks(canvas, face, bitmap.width, bitmap.height)
        }

        drawStats(canvas, drewFacePatch)
    }

    private fun drawPixelatedFace(canvas: Canvas, bitmap: Bitmap, source: Rect, destination: RectF) {
        val smallW = max(8, source.width() / 14)
        val smallH = max(8, source.height() / 14)
        val tiny = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        val tinyCanvas = Canvas(tiny)
        tinyRect.set(0, 0, smallW, smallH)
        tinyCanvas.drawBitmap(bitmap, source, tinyRect, bitmapPaint)
        canvas.drawBitmap(tiny, null, destination, bitmapPaint)
        tiny.recycle()
    }

    private fun drawLandmarks(canvas: Canvas, face: DetectedFace, imageWidth: Int, imageHeight: Int) {
        face.landmarks.forEach { (x, y) ->
            val p = RectF(x - 1f, y - 1f, x + 1f, y + 1f)
            mapImageRectToView(p, imageWidth, imageHeight, mappedRect)
            canvas.drawCircle(mappedRect.centerX(), mappedRect.centerY(), 4f, landmarkPaint)
        }
    }

    private fun drawStats(canvas: Canvas, drewFacePatch: Boolean) {
        val s = stats ?: return
        val left = 12f
        val top = (statsTopInsetPx + 8f).coerceAtMost((height - STATS_PANEL_HEIGHT - 12f).coerceAtLeast(12f))
        val right = width.coerceAtMost(760).toFloat()
        canvas.drawRect(left, top, right, top + STATS_PANEL_HEIGHT, panelPaint)
        canvas.drawText(
            "${s.detectorName} ${s.faceSource} ${s.faceCount} face(s) ${s.frameWidth}x${s.frameHeight}",
            24f,
            top + 30f,
            textPaint
        )
        canvas.drawText(
            "Convert ${s.conversionMs.format1()} ms  CNN ${s.inferenceMs.format1()} ms  Total ${s.totalAnalysisMs.format1()} ms",
            24f,
            top + 62f,
            textPaint
        )
        val privacyText = if (drewFacePatch) "Face patch" else "No face patch"
        canvas.drawText(
            "$privacyText  Camera ${s.frameFps.format1()} FPS  Age ${s.predictionAgeMs.format1()} ms",
            24f,
            top + 94f,
            textPaint
        )
        canvas.drawText(
            "IMU ${s.imuShiftX.format1()},${s.imuShiftY.format1()} px  UWB ${s.uwbShiftX.format1()},${s.uwbShiftY.format1()} px",
            24f,
            top + 126f,
            textPaint
        )
    }

    private fun mapImageToViewRect(imageWidth: Int, imageHeight: Int, out: RectF) {
        val scale = max(width / imageWidth.toFloat(), height / imageHeight.toFloat())
        val dx = (width - imageWidth * scale) / 2f
        val dy = (height - imageHeight * scale) / 2f
        out.set(dx, dy, dx + imageWidth * scale, dy + imageHeight * scale)
    }

    private fun mapImageRectToView(source: RectF, imageWidth: Int, imageHeight: Int, out: RectF) {
        val scale = max(width / imageWidth.toFloat(), height / imageHeight.toFloat())
        val dx = (width - imageWidth * scale) / 2f
        val dy = (height - imageHeight * scale) / 2f
        out.set(
            source.left * scale + dx,
            source.top * scale + dy,
            source.right * scale + dx,
            source.bottom * scale + dy
        )
    }

    private fun RectF.expand(widthFraction: Float, heightFraction: Float, maxWidth: Int, maxHeight: Int): RectF {
        val growX = width() * widthFraction * 0.5f
        val growY = height() * heightFraction * 0.5f
        return RectF(
            (left - growX).coerceAtLeast(0f),
            (top - growY).coerceAtLeast(0f),
            (right + growX).coerceAtMost(maxWidth.toFloat()),
            (bottom + growY).coerceAtMost(maxHeight.toFloat())
        )
    }

    private fun Float.format1(): String = String.format("%.1f", this)

    companion object {
        private const val STATS_PANEL_HEIGHT = 138f
    }
}
