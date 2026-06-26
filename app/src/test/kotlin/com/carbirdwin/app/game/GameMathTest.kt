package com.carbirdwin.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMathTest {
    private val bounds = CollisionBounds(
        barrierWidth = 80f,
        clearanceHeight = 180f,
        worldHeight = 800f,
        roadTop = 720f,
    )

    @Test
    fun vehicleInsideClearanceDoesNotHitBarrier() {
        val barriers = listOf(FlightBarrier(x = 200f, clearanceCenterY = 360f))

        assertFalse(vehicleHitsBarrier(240f, 360f, 24f, barriers, bounds))
    }

    @Test
    fun vehicleTouchingTopBarrierHitsObstacle() {
        val barriers = listOf(FlightBarrier(x = 200f, clearanceCenterY = 360f))

        assertTrue(vehicleHitsBarrier(240f, 250f, 24f, barriers, bounds))
    }

    @Test
    fun clearingBarrierAddsOneScoreOnlyOnce() {
        val barriers = listOf(FlightBarrier(x = 100f, clearanceCenterY = 360f))

        val (firstUpdate, firstScore) = markClearedBarriers(
            vehicleX = 220f,
            barriers = barriers,
            barrierWidth = 80f,
        )
        val (_, secondScore) = markClearedBarriers(
            vehicleX = 260f,
            barriers = firstUpdate,
            barrierWidth = 80f,
        )

        assertEquals(1, firstScore)
        assertEquals(0, secondScore)
    }
}
