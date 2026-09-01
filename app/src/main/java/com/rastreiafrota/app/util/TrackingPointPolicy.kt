package com.rastreiafrota.app.util

import kotlin.math.abs
import kotlin.math.min

/** Regras puras para decidir a densidade do trajeto sem depender do Android. */
object TrackingPointPolicy {
    const val MOVING_SPEED_KMH = 3.0

    // Fidelidade do traçado: um ponto a cada 10 s em movimento deixava 160 m de reta a
    // 60 km/h e a linha cortava a curva. Com 2 s o desenho acompanha a via.
    const val MAX_MOVING_GAP_SEC = 2.0
    const val MIN_STOPPED_POINT_GAP_SEC = 15.0
    const val STOPPED_JITTER_DISTANCE_M = 2.0
    const val LOW_ACCURACY_POINT_GAP_MS = 30_000L

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
        accuracyM: Double?,
        configuredMaxAccuracyM: Int,
        millisSinceLastSaved: Long,
        configuredMinDistanceM: Int,
        previousBearing: Double? = null,
        currentBearing: Double? = null
    ): Boolean {
        if (!hasPrevious) return true

        val lowAccuracy = accuracyM != null && accuracyM > configuredMaxAccuracyM
        if (lowAccuracy && millisSinceLastSaved < LOW_ACCURACY_POINT_GAP_MS) return false

        // Curva: mudou o rumo de forma relevante, grava mesmo sem ter andado a distância
        // mínima. Isso impede a linha de "cortar" a esquina.
        if (
            movementSpeedKmh > MOVING_SPEED_KMH &&
            headingDeltaDeg(previousBearing, currentBearing) >= HEADING_CHANGE_DEG
        ) {
            return true
        }

        return if (movementSpeedKmh > MOVING_SPEED_KMH) {
            distanceM >= effectiveMinDistance(configuredMinDistanceM, movementSpeedKmh) ||
                elapsedSeconds >= MAX_MOVING_GAP_SEC
        } else {
            distanceM >= STOPPED_JITTER_DISTANCE_M ||
                elapsedSeconds >= MIN_STOPPED_POINT_GAP_SEC
        }
    }
}
