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
    fun neverTurnsStationaryGpsJitterIntoRoutePoints() {
        assertFalse(
            decision(
                distanceM = 12.0,
                elapsedSeconds = 60.0,
                speedKmh = 0.0,
                reliableMovement = false
            )
        )
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
    fun rejectsLowAccuracyInsteadOfDrawingFalseLines() {
        assertFalse(
            decision(
                distanceM = 20.0,
                elapsedSeconds = 10.0,
                speedKmh = 15.0,
                accuracyM = 26.0
            )
        )
    }

    @Test
    fun firstPointRequiresStrongerAccuracy() {
        assertTrue(
            TrackingPointPolicy.shouldStore(
                hasPrevious = false,
                distanceM = Double.POSITIVE_INFINITY,
                elapsedSeconds = Double.POSITIVE_INFINITY,
                movementSpeedKmh = 0.0,
                reliableMovement = true,
                accuracyM = 15.0,
                configuredMaxAccuracyM = 50,
                configuredMinDistanceM = 20
            )
        )
        assertFalse(
            TrackingPointPolicy.shouldStore(
                hasPrevious = false,
                distanceM = Double.POSITIVE_INFINITY,
                elapsedSeconds = Double.POSITIVE_INFINITY,
                movementSpeedKmh = 0.0,
                reliableMovement = true,
                accuracyM = 15.1,
                configuredMaxAccuracyM = 50,
                configuredMinDistanceM = 20
            )
        )
    }

    @Test
    fun displacementMustExceedTheCombinedGpsUncertainty() {
        val radius = TrackingPointPolicy.uncertaintyRadius(10.0, 10.0)
        assertEquals(17.67, radius, 0.02)
        assertFalse(TrackingPointPolicy.hasReliableDisplacement(17.0, 10.0, 10.0))
        assertTrue(TrackingPointPolicy.hasReliableDisplacement(18.0, 10.0, 10.0))
    }

    @Test
    fun speedMustRemainPositiveAfterSubtractingItsError() {
        assertTrue(TrackingPointPolicy.hasReliableReportedMovement(5.0, 1.0))
        assertFalse(TrackingPointPolicy.hasReliableReportedMovement(5.0, 4.0))
        assertFalse(TrackingPointPolicy.hasReliableReportedMovement(3.0, null))
    }

    @Test
    fun smoothsWalkingMoreThanFastVehicleMovement() {
        assertEquals(0.50, TrackingPointPolicy.smoothingAlpha(14.0, 5.0), 0.0)
        assertEquals(0.80, TrackingPointPolicy.smoothingAlpha(14.0, 40.0), 0.0)
    }

    private fun decision(
        distanceM: Double,
        elapsedSeconds: Double,
        speedKmh: Double,
        reliableMovement: Boolean = true,
        accuracyM: Double? = 10.0,
        previousBearing: Double? = null,
        currentBearing: Double? = null
    ): Boolean = TrackingPointPolicy.shouldStore(
        hasPrevious = true,
        distanceM = distanceM,
        elapsedSeconds = elapsedSeconds,
        movementSpeedKmh = speedKmh,
        reliableMovement = reliableMovement,
        accuracyM = accuracyM,
        configuredMaxAccuracyM = 50,
        configuredMinDistanceM = 20,
        previousBearing = previousBearing,
        currentBearing = currentBearing
    )
}
