package com.carbirdwin.app.game

import kotlin.math.pow

data class FlightBarrier(
    val x: Float,
    val clearanceCenterY: Float,
    val cleared: Boolean = false,
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
    val barrierWidth: Float,
    val clearanceHeight: Float,
    val worldHeight: Float,
    val roadTop: Float,
)

fun FlightBarrier.topBarrierRect(bounds: CollisionBounds): GameRect =
    GameRect(x, 0f, x + bounds.barrierWidth, clearanceCenterY - bounds.clearanceHeight / 2f)

fun FlightBarrier.bottomBarrierRect(bounds: CollisionBounds): GameRect =
    GameRect(x, clearanceCenterY + bounds.clearanceHeight / 2f, x + bounds.barrierWidth, bounds.roadTop)

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

fun vehicleHitsBarrier(
    vehicleX: Float,
    vehicleY: Float,
    vehicleRadius: Float,
    barriers: List<FlightBarrier>,
    bounds: CollisionBounds,
): Boolean = barriers.any { barrier ->
    circleIntersectsRect(vehicleX, vehicleY, vehicleRadius, barrier.topBarrierRect(bounds)) ||
        circleIntersectsRect(vehicleX, vehicleY, vehicleRadius, barrier.bottomBarrierRect(bounds))
}

fun markClearedBarriers(
    vehicleX: Float,
    barriers: List<FlightBarrier>,
    barrierWidth: Float,
): Pair<List<FlightBarrier>, Int> {
    var gained = 0
    val updated = barriers.map { barrier ->
        if (!barrier.cleared && barrier.x + barrierWidth < vehicleX) {
            gained += 1
            barrier.copy(cleared = true)
        } else {
            barrier
        }
    }
    return updated to gained
}
