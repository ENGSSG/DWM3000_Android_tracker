package com.example.pixeluwb.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A simple 2D radar/polar view that plots the peer device's position
 * based on distance (range) and azimuth angle of arrival.
 *
 * The current device is at the center. Concentric rings represent distance.
 * The peer is plotted as a dot at (angle, distance) in polar coordinates.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var distanceMeters: Float = 0f
    private var azimuthDegrees: Float = 0f
    private var maxRangeMeters: Float = 10f  // Max displayable range
    private var hasPeer: Boolean = false

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334444")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#446666")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88AAAA")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88")
        style = Paint.Style.FILL
    }

    private val dotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4400FF88")
        style = Paint.Style.FILL
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00AAFF")
        style = Paint.Style.FILL
    }

    private val fovPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1800FF88")
        style = Paint.Style.FILL
    }

    fun updatePosition(distance: Float, azimuth: Float) {
        this.distanceMeters = distance
        this.azimuthDegrees = azimuth
        this.hasPeer = true

        // Auto-scale: keep the max range at least 2m beyond the peer
        maxRangeMeters = maxOf(10f, distance + 2f)

        invalidate()
    }

    fun clearPeer() {
        hasPeer = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.85f

        canvas.drawColor(Color.parseColor("#0A1A1A"))

        // Draw FoV cone (UWB typically ±90° azimuth)
        val fovPath = Path()
        fovPath.moveTo(cx, cy)
        val fovRadius = radius * 1.05f
        fovPath.lineTo(
            cx + fovRadius * sin(Math.toRadians(-90.0)).toFloat(),
            cy - fovRadius * cos(Math.toRadians(-90.0)).toFloat()
        )
        fovPath.arcTo(
            cx - fovRadius, cy - fovRadius, cx + fovRadius, cy + fovRadius,
            -180f, 180f, false
        )
        fovPath.close()
        canvas.drawPath(fovPath, fovPaint)

        // Draw concentric range rings
        val ringCount = 5
        for (i in 1..ringCount) {
            val ringRadius = radius * i / ringCount
            canvas.drawCircle(cx, cy, ringRadius, gridPaint)

            val rangeLabel = String.format("%.0fm", maxRangeMeters * i / ringCount)
            canvas.drawText(rangeLabel, cx + 8, cy - ringRadius + 20, labelPaint)
        }

        // Draw axis lines (N, E, S, W)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, axisPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, axisPaint)

        // Draw 45° lines
        val diag = radius * 0.707f
        canvas.drawLine(cx - diag, cy - diag, cx + diag, cy + diag, axisPaint)
        canvas.drawLine(cx + diag, cy - diag, cx - diag, cy + diag, axisPaint)

        // Draw center point (this device)
        canvas.drawCircle(cx, cy, 6f, centerPaint)

        // Draw peer device
        if (hasPeer) {
            val clampedDist = distanceMeters.coerceIn(0f, maxRangeMeters)
            val normalizedDist = clampedDist / maxRangeMeters

            // Convert polar to screen coordinates
            // Azimuth: 0° = straight ahead (up), positive = right
            val angleRad = Math.toRadians(azimuthDegrees.toDouble())
            val px = cx + (normalizedDist * radius * sin(angleRad)).toFloat()
            val py = cy - (normalizedDist * radius * cos(angleRad)).toFloat()

            // Glow
            canvas.drawCircle(px, py, 24f, dotGlowPaint)
            canvas.drawCircle(px, py, 16f, dotGlowPaint)

            // Dot
            canvas.drawCircle(px, py, 10f, dotPaint)
        }
    }
}
