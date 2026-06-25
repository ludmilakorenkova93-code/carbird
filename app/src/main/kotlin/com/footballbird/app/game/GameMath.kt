package com.footballbird.app.game

import kotlin.math.pow

data class Pipe(
    val x: Float,
    val gapCenterY: Float,
    val passed: Boolean = false,
)

data class GameRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun height(): Float = bottom - top
}

data class CollisionBounds(
    val pipeWidth: Float,
    val gapHeight: Float,
    val worldHeight: Float,
    val groundTop: Float,
)

fun Pipe.topRect(bounds: CollisionBounds): GameRect =
    GameRect(x, 0f, x + bounds.pipeWidth, gapCenterY - bounds.gapHeight / 2f)

fun Pipe.bottomRect(bounds: CollisionBounds): GameRect =
    GameRect(x, gapCenterY + bounds.gapHeight / 2f, x + bounds.pipeWidth, bounds.groundTop)

fun circleIntersectsRect(
    centerX: Float,
    centerY: Float,
    radius: Float,
    rect: GameRect,
): Boolean {
    val closestX = centerX.coerceIn(rect.left, rect.right)
    val closestY = centerY.coerceIn(rect.top, rect.bottom)
    val dx = centerX - closestX
    val dy = centerY - closestY
    return dx.pow(2) + dy.pow(2) <= radius.pow(2)
}

fun hitsObstacle(
    ballX: Float,
    ballY: Float,
    ballRadius: Float,
    pipes: List<Pipe>,
    bounds: CollisionBounds,
): Boolean = pipes.any { pipe ->
    circleIntersectsRect(ballX, ballY, ballRadius, pipe.topRect(bounds)) ||
        circleIntersectsRect(ballX, ballY, ballRadius, pipe.bottomRect(bounds))
}

fun updatePassedPipes(
    ballX: Float,
    pipes: List<Pipe>,
    pipeWidth: Float,
): Pair<List<Pipe>, Int> {
    var gained = 0
    val updated = pipes.map { pipe ->
        if (!pipe.passed && pipe.x + pipeWidth < ballX) {
            gained += 1
            pipe.copy(passed = true)
        } else {
            pipe
        }
    }
    return updated to gained
}
