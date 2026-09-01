package com.rastreiafrota.app.util

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Regras puras para decidir a densidade do trajeto sem depender do Android. */
object TrackingPointPolicy {
    const val MOVING_SPEED_KMH = 3.0

    // Fidelidade do traçado: um ponto a cada 10 s em movimento deixava 160 m de reta a
    // 60 km/h e a linha cortava a curva. Com 2 s o desenho acompanha a via.
    const val MAX_MOVING_GAP_SEC = 2.0
    const val MAX_ROUTE_ACCURACY_M = 25.0
    const val MAX_INITIAL_ACCURACY_M = 15.0
    const val MIN_UNCERTAINTY_RADIUS_M = 4.0
    const val MAX_UNCERTAINTY_RADIUS_M = 35.0
    const val ACCURACY_MARGIN_FACTOR = 1.25
    const val MIN_RELIABLE_SPEED_KMH = 2.5

    /** Mudança de rumo que obriga a gravar o ponto e preserva a curva. */
    const val HEADING_CHANGE_DEG = 12.0

    fun effectiveMinDistance(configuredMinDistanceM: Int, movementSpeedKmh: Double): Double {
        val configured = configuredMinDistanceM.coerceIn(1, 100).toDouble()
        return when {
            movementSpeedKmh >= 50.0 -> min(configured, 10.0)
            movementSpeedKmh >= 10.0 -> min(configured, 5.0)
            else -> min(configured, 2.0)
        }
    }

    /**
     * O primeiro ponto precisa ser mais preciso porque ele vira a âncora do percurso.
     * Pontos acima do limite não entram no desenho; o status do aparelho continua sendo enviado.
     */
    fun hasUsableAccuracy(
        hasPrevious: Boolean,
        accuracyM: Double?,
        configuredMaxAccuracyM: Int
    ): Boolean {
        if (accuracyM == null || !accuracyM.isFinite() || accuracyM <= 0.0) return false
        val configured = configuredMaxAccuracyM.coerceIn(5, 100).toDouble()
        val hardLimit = if (hasPrevious) MAX_ROUTE_ACCURACY_M else MAX_INITIAL_ACCURACY_M
        return accuracyM <= min(configured, hardLimit)
    }

    /**
     * Margem necessária para diferenciar deslocamento verdadeiro de oscilação do GPS.
     * Duas leituras de 10 m de precisão podem divergir vários metros sem o celular se mover.
     */
    fun uncertaintyRadius(previousAccuracyM: Double?, currentAccuracyM: Double?): Double {
        val previous = previousAccuracyM?.takeIf { it.isFinite() && it > 0.0 } ?: MAX_ROUTE_ACCURACY_M
        val current = currentAccuracyM?.takeIf { it.isFinite() && it > 0.0 } ?: MAX_ROUTE_ACCURACY_M
        return (sqrt(previous * previous + current * current) * ACCURACY_MARGIN_FACTOR)
            .coerceIn(MIN_UNCERTAINTY_RADIUS_M, MAX_UNCERTAINTY_RADIUS_M)
    }

    fun hasReliableDisplacement(
        distanceM: Double,
        previousAccuracyM: Double?,
        currentAccuracyM: Double?
    ): Boolean = distanceM.isFinite() &&
        distanceM >= uncertaintyRadius(previousAccuracyM, currentAccuracyM)

    /** Usa o limite inferior da velocidade, descontando a incerteza informada pelo GNSS. */
    fun hasReliableReportedMovement(
        speedKmh: Double?,
        speedAccuracyKmh: Double?
    ): Boolean {
        if (speedKmh == null || !speedKmh.isFinite() || speedKmh < 0.0) return false
        val uncertainty = speedAccuracyKmh
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: max(2.0, speedKmh * 0.5)
        return speedKmh - uncertainty >= MIN_RELIABLE_SPEED_KMH
    }

    /** Quanto menor a precisão, maior a suavização aplicada antes de salvar a coordenada. */
    fun smoothingAlpha(accuracyM: Double, movementSpeedKmh: Double): Double {
        val base = when {
            accuracyM <= 5.0 -> 0.85
            accuracyM <= 10.0 -> 0.65
            accuracyM <= 15.0 -> 0.50
            else -> 0.35
        }
        return if (movementSpeedKmh >= 15.0) max(base, 0.80) else base
    }

    /** Diferença angular entre dois rumos, sempre no intervalo 0..180 graus. */
    fun headingDeltaDeg(previousBearing: Double?, currentBearing: Double?): Double {
        if (previousBearing == null || currentBearing == null) return 0.0
        var delta = abs(currentBearing - previousBearing) % 360.0
        if (delta > 180.0) delta = 360.0 - delta
        return delta
    }

    fun shouldStore(
        hasPrevious: Boolean,
        distanceM: Double,
        elapsedSeconds: Double,
        movementSpeedKmh: Double,
        reliableMovement: Boolean,
        accuracyM: Double?,
        configuredMaxAccuracyM: Int,
        configuredMinDistanceM: Int,
        previousBearing: Double? = null,
        currentBearing: Double? = null
    ): Boolean {
        if (!hasUsableAccuracy(hasPrevious, accuracyM, configuredMaxAccuracyM)) return false
        if (!hasPrevious) return true
        if (!reliableMovement) return false

        // Curva: mudou o rumo de forma relevante, grava mesmo sem ter andado a distância
        // mínima. Isso impede a linha de "cortar" a esquina.
        if (
            movementSpeedKmh > MOVING_SPEED_KMH &&
            headingDeltaDeg(previousBearing, currentBearing) >= HEADING_CHANGE_DEG
        ) {
            return true
        }

        return distanceM >= effectiveMinDistance(configuredMinDistanceM, movementSpeedKmh) ||
            elapsedSeconds >= MAX_MOVING_GAP_SEC
    }
}
