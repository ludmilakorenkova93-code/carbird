package com.footballbird.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMathTest {
    private val bounds = CollisionBounds(
        pipeWidth = 80f,
        gapHeight = 180f,
        worldHeight = 800f,
        groundTop = 720f,
    )

    @Test
    fun ballInsideGapDoesNotHitObstacle() {
        val pipes = listOf(Pipe(x = 200f, gapCenterY = 360f))

        assertFalse(hitsObstacle(240f, 360f, 24f, pipes, bounds))
    }

    @Test
    fun ballTouchingTopPipeHitsObstacle() {
        val pipes = listOf(Pipe(x = 200f, gapCenterY = 360f))

        assertTrue(hitsObstacle(240f, 250f, 24f, pipes, bounds))
    }

    @Test
    fun passingPipeAddsOneScoreOnlyOnce() {
        val pipes = listOf(Pipe(x = 100f, gapCenterY = 360f))

        val (firstUpdate, firstScore) = updatePassedPipes(
            ballX = 220f,
            pipes = pipes,
            pipeWidth = 80f,
        )
        val (_, secondScore) = updatePassedPipes(
            ballX = 260f,
            pipes = firstUpdate,
            pipeWidth = 80f,
        )

        assertEquals(1, firstScore)
        assertEquals(0, secondScore)
    }
}
