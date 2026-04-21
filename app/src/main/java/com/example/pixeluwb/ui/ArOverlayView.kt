package com.example.pixeluwb.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ArOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    val mapper = AoAScreenMapper()

    private var dist = 0f; private var az = 0f; private var el = 0f
    private var hasPeer = false; private var pulse = 0f
    private var sx = 0f; private var sy = 0f; private var first = true

    private val outerP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00FF88"); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val innerP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00FF88"); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val fillP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2200FF88"); style = Paint.Style.FILL }
    private val crossP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#88FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val dotP   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00FF88"); style = Paint.Style.FILL }
    private val textP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER; isFakeBoldText = true; setShadowLayer(6f,0f,2f,Color.parseColor("#AA000000")) }
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#AAFFFFFF"); textSize = 28f; textAlign = Paint.Align.CENTER; setShadowLayer(4f,0f,1f,Color.parseColor("#88000000")) }
    private val arrowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00FF88"); style = Paint.Style.FILL }
    private val glowP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4400FF88"); style = Paint.Style.FILL }

    fun updatePosition(distance: Float, azimuth: Float, elevation: Float) {
        dist = distance; az = azimuth; el = elevation; hasPeer = true
        pulse = (pulse + 0.08f) % 1f; invalidate()
    }

    fun clearPeer() { hasPeer = false; first = true; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasPeer) return

        val inFov = mapper.isInFov(az, el)
        val pos = mapper.mapToScreen(az, el, dist, width, height)

        if (first) { sx = pos.x; sy = pos.y; first = false }
        else { sx += 0.3f * (pos.x - sx); sy += 0.3f * (pos.y - sy) }

        if (inFov) drawReticle(canvas, sx, sy) else drawEdgeArrow(canvas, sx, sy)
    }

    private fun drawReticle(canvas: Canvas, cx: Float, cy: Float) {
        val r = 60f * (1f / dist.coerceAtLeast(0.3f)).coerceIn(0.3f, 2.5f)
        val pr = r * (1f + 0.3f * sin(pulse * 2 * Math.PI).toFloat())
        fillP.alpha = (34 * (1f - pulse * 0.5f)).toInt()
        canvas.drawCircle(cx, cy, pr, fillP)
        outerP.alpha = (255 * (0.5f + 0.5f * cos(pulse * 2 * Math.PI).toFloat())).toInt()
        canvas.drawCircle(cx, cy, pr, outerP)
        canvas.drawCircle(cx, cy, r * 0.5f, innerP)
        val t = r * 0.3f; val g = r * 0.6f
        canvas.drawLine(cx, cy-g-t, cx, cy-g, crossP)
        canvas.drawLine(cx, cy+g, cx, cy+g+t, crossP)
        canvas.drawLine(cx-g-t, cy, cx-g, cy, crossP)
        canvas.drawLine(cx+g, cy, cx+g+t, cy, crossP)
        canvas.drawCircle(cx, cy, 4f, dotP)
        canvas.drawText(String.format("%.2f m", dist), cx, cy+pr+52f, textP)
        canvas.drawText(String.format("Az %.1f°  El %.1f°", az, el), cx, cy+pr+86f, labelP)
    }

    private fun drawEdgeArrow(canvas: Canvas, tx: Float, ty: Float) {
        val m = 80f; val cx = tx.coerceIn(m, width-m); val cy = ty.coerceIn(m, height-m)
        val a = atan2(ty - height/2f, tx - width/2f)
        canvas.drawCircle(cx, cy, 40f, glowP); canvas.drawCircle(cx, cy, 28f, glowP)
        val s = 24f
        val p = Path().apply {
            moveTo(cx+s*cos(a), cy+s*sin(a))
            lineTo(cx+s*0.6f*cos(a+2.5f), cy+s*0.6f*sin(a+2.5f))
            lineTo(cx+s*0.6f*cos(a-2.5f), cy+s*0.6f*sin(a-2.5f)); close()
        }
        canvas.drawPath(p, arrowP)
        canvas.drawText(String.format("%.1f m", dist), cx, if (cy < height/2f) cy+60f else cy-40f, textP)
    }
}