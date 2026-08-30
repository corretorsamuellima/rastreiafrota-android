package com.rastreiafrota.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.rastreiafrota.app.data.repository.RouteTrailPoint
import kotlin.math.cos

/**
 * Miniatura vetorial do trajeto ativo. Não usa chave de mapa, funciona offline e deixa
 * explícito ao motorista quais pontos já foram registrados no aparelho.
 */
class RouteTrailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 189, 248)
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 148, 163, 184)
        strokeWidth = 1f
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 197, 94) }
    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 113, 113) }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(148, 163, 184)
        textAlign = Paint.Align.CENTER
        textSize = 34f
    }

    private var points: List<RouteTrailPoint> = emptyList()

    fun setRoutePoints(value: List<RouteTrailPoint>) {
        points = value
        contentDescription = if (value.isEmpty()) "Trajeto ainda sem pontos" else "Trajeto com ${value.size} pontos"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        if (points.isEmpty()) {
            canvas.drawText("Aguardando o primeiro ponto…", width / 2f, height / 2f, emptyPaint)
            return
        }

        val meanLatRad = Math.toRadians(points.map { it.latitude }.average())
        val projected = points.map { (it.longitude * cos(meanLatRad)) to it.latitude }
        val minX = projected.minOf { it.first }
        val maxX = projected.maxOf { it.first }
        val minY = projected.minOf { it.second }
        val maxY = projected.maxOf { it.second }
        val rangeX = (maxX - minX).coerceAtLeast(0.00001)
        val rangeY = (maxY - minY).coerceAtLeast(0.00001)
        val pad = 28f
        val usableW = (width - pad * 2).coerceAtLeast(1f)
        val usableH = (height - pad * 2).coerceAtLeast(1f)
        val scale = minOf(usableW / rangeX.toFloat(), usableH / rangeY.toFloat())
        val drawnW = rangeX.toFloat() * scale
        val drawnH = rangeY.toFloat() * scale
        val left = (width - drawnW) / 2f
        val top = (height - drawnH) / 2f

        fun screen(index: Int): Pair<Float, Float> {
            val p = projected[index]
            val x = left + ((p.first - minX).toFloat() * scale)
            val y = top + drawnH - ((p.second - minY).toFloat() * scale)
            return x to y
        }

        val path = Path()
        projected.indices.forEach { index ->
            val (x, y) = screen(index)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, routePaint)
        val (startX, startY) = screen(0)
        val (endX, endY) = screen(projected.lastIndex)
        canvas.drawCircle(startX, startY, 10f, startPaint)
        canvas.drawCircle(endX, endY, 10f, endPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        for (i in 1..3) {
            val x = width * i / 4f
            val y = height * i / 4f
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }
    }
}
