package com.rastreiafrota.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingPointPolicyTest {

    @Test
    fun usesDenseDistanceForWalking() {
        assertEquals(2.0, TrackingPointPolicy.effectiveMinDistance(20, 5.0), 0.0)
        assertFalse(decision(distanceM = 1.9, elapsedSeconds = 1.0, speedKmh = 5.0))
        assertTrue(decision(distanceM = 2.0, elapsedSeconds = 1.0, speedKmh = 5.0))
    }

    @Test
    fun keepsUrbanPointsWithinFiveMeters() {
        assertEquals(5.0, TrackingPointPolicy.effectiveMinDistance(20, 35.0), 0.0)
        assertTrue(decision(distanceM = 5.0, elapsedSeconds = 1.0, speedKmh = 35.0))
    }

    @Test
    fun storesMovingPointAtLeastEveryTwoSeconds() {
        assertFalse(decision(distanceM = 1.0, elapsedSeconds = 1.9, speedKmh = 12.0))
        assertTrue(decision(distanceM = 1.0, elapsedSeconds = 2.0, speedKmh = 12.0))
    }

    @Test
    fun storesStationaryHeartbeatEveryFifteenSeconds() {
        assertFalse(decision(distanceM = 0.5, elapsedSeconds = 14.9, speedKmh = 0.0))
        assertTrue(decision(distanceM = 0.5, elapsedSeconds = 15.0, speedKmh = 0.0))
    }

    @Test
    fun storesRelevantHeadingChangeToPreserveCurves() {
        assertTrue(
            decision(
                distanceM = 0.5,
                elapsedSeconds = 0.5,
                speedKmh = 20.0,
                previousBearing = 5.0,
                currentBearing = 353.0
            )
        )
        assertFalse(
            decision(
                distanceM = 0.5,
                elapsedSeconds = 0.5,
                speedKmh = 20.0,
                previousBearing = 5.0,
                currentBearing = 356.0
            )
        )
    }

    @Test
    fun throttlesLowAccuracyWithoutDroppingTheWholeRoute() {
        assertFalse(
            decision(
                distanceM = 20.0,
                elapsedSeconds = 10.0,
                speedKmh = 15.0,
                accuracyM = 90.0,
                millisSinceLastSaved = 20_000L
            )
        )
        assertTrue(
            decision(
                distanceM = 20.0,
                elapsedSeconds = 30.0,
                speedKmh = 15.0,
                accuracyM = 90.0,
                millisSinceLastSaved = 30_000L
            )
        )
    }

    @Test
    fun alwaysStoresFirstValidPoint() {
        assertTrue(
            TrackingPointPolicy.shouldStore(
                hasPrevious = false,
                distanceM = Double.POSITIVE_INFINITY,
                elapsedSeconds = Double.POSITIVE_INFINITY,
                movementSpeedKmh = 0.0,
                accuracyM = 100.0,
                configuredMaxAccuracyM = 50,
                millisSinceLastSaved = 0L,
                configuredMinDistanceM = 20
            )
        )
    }

    private fun decision(
        distanceM: Double,
        elapsedSeconds: Double,
        speedKmh: Double,
        accuracyM: Double? = 10.0,
        millisSinceLastSaved: Long = 60_000L,
        previousBearing: Double? = null,
        currentBearing: Double? = null
    ): Boolean = TrackingPointPolicy.shouldStore(
        hasPrevious = true,
        distanceM = distanceM,
        elapsedSeconds = elapsedSeconds,
        movementSpeedKmh = speedKmh,
        accuracyM = accuracyM,
        configuredMaxAccuracyM = 50,
        millisSinceLastSaved = millisSinceLastSaved,
        configuredMinDistanceM = 20,
        previousBearing = previousBearing,
        currentBearing = currentBearing
    )
}
