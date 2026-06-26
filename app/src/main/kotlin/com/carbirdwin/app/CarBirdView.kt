package com.carbirdwin.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.carbirdwin.app.game.CollisionBounds
import com.carbirdwin.app.game.FlightBarrier
import com.carbirdwin.app.game.GameRect
import com.carbirdwin.app.game.bottomBarrierRect
import com.carbirdwin.app.game.markClearedBarriers
import com.carbirdwin.app.game.topBarrierRect
import com.carbirdwin.app.game.vehicleHitsBarrier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class CarBirdView(context: Context) : View(context), Choreographer.FrameCallback {
    private enum class Screen {
        Garage,
        Flying,
        Crash,
    }

    private enum class CarSkin(
        val storageKey: String,
        val titleRes: Int,
        val bodyColor: Int,
        val roofColor: Int,
        val accentColor: Int,
    ) {
        Cruiser(
            storageKey = "cruiser",
            titleRes = R.string.car_cruiser,
            bodyColor = Color.rgb(41, 128, 185),
            roofColor = Color.rgb(236, 248, 255),
            accentColor = Color.rgb(255, 198, 58),
        ),
        Taxi(
            storageKey = "taxi",
            titleRes = R.string.car_taxi,
            bodyColor = Color.rgb(246, 190, 45),
            roofColor = Color.rgb(35, 42, 48),
            accentColor = Color.rgb(22, 132, 92),
        ),
        Sprint(
            storageKey = "sprint",
            titleRes = R.string.car_sprint,
            bodyColor = Color.rgb(224, 77, 69),
            roofColor = Color.rgb(32, 38, 44),
            accentColor = Color.rgb(115, 224, 210),
        ),
    }

    private val prefs = context.getSharedPreferences("car_bird_game", Context.MODE_PRIVATE)
    private val random = Random(System.currentTimeMillis())
    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private var screen = Screen.Garage
    private var selectedCar = CarSkin.entries.firstOrNull {
        it.storageKey == prefs.getString(KEY_SELECTED_CAR, CarSkin.Cruiser.storageKey)
    } ?: CarSkin.Cruiser

    private var highScore = prefs.getInt(KEY_HIGH_SCORE, 0)
    private var score = 0
    private var carX = 0f
    private var carY = 0f
    private var carVelocity = 0f
    private var carPitch = 0f
    private var elapsedSeconds = 0f
    private var barrierTimer = 0f
    private var lastFrameTimeNs = 0L
    private var isRunning = false
    private var barriers = mutableListOf<FlightBarrier>()

    private val carButtons = mutableMapOf<CarSkin, RectF>()
    private val playButton = RectF()
    private val retryButton = RectF()
    private val garageButton = RectF()

    init {
        isFocusable = true
    }

    fun resume() {
        if (!isRunning) {
            isRunning = true
            lastFrameTimeNs = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun pause() {
        if (isRunning) {
            isRunning = false
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return

        if (lastFrameTimeNs != 0L) {
            val deltaSeconds = ((frameTimeNanos - lastFrameTimeNs) / NANOS_PER_SECOND)
                .coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
            if (screen == Screen.Flying) updateGame(deltaSeconds)
        }

        lastFrameTimeNs = frameTimeNanos
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        layoutControls(width.toFloat(), height.toFloat())
        if (carX == 0f) resetCar()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true

        when (screen) {
            Screen.Garage -> handleGarageTap(event.x, event.y)
            Screen.Flying -> boost()
            Screen.Crash -> handleCrashTap(event.x, event.y)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        layoutControls(width.toFloat(), height.toFloat())

        when (screen) {
            Screen.Garage -> drawGarage(canvas)
            Screen.Flying -> drawFlight(canvas, showHint = score == 0 && elapsedSeconds < 3.2f)
            Screen.Crash -> {
                drawFlight(canvas, showHint = false)
                drawCrashOverlay(canvas)
            }
        }
    }

    private fun handleGarageTap(x: Float, y: Float) {
        val tappedCar = carButtons.entries.firstOrNull { (_, rect) -> rect.contains(x, y) }?.key
        if (tappedCar != null) {
            selectedCar = tappedCar
            prefs.edit().putString(KEY_SELECTED_CAR, selectedCar.storageKey).apply()
            invalidate()
            return
        }

        if (playButton.contains(x, y)) startGame()
    }

    private fun handleCrashTap(x: Float, y: Float) {
        when {
            retryButton.contains(x, y) -> startGame()
            garageButton.contains(x, y) -> {
                screen = Screen.Garage
                resetCar()
                invalidate()
            }
            else -> startGame()
        }
    }

    private fun startGame() {
        score = 0
        elapsedSeconds = 0f
        barrierTimer = 0f
        barriers.clear()
        resetCar()
        screen = Screen.Flying
        invalidate()
    }

    private fun resetCar() {
        carX = width * CAR_X_RATIO
        carY = height * START_Y_RATIO
        carVelocity = 0f
        carPitch = 0f
    }

    private fun boost() {
        carVelocity = -height * BOOST_STRENGTH_RATIO
    }

    private fun updateGame(deltaSeconds: Float) {
        if (width == 0 || height == 0) return

        elapsedSeconds += deltaSeconds
        val roadTop = roadTop()
        val radius = vehicleRadius()
        val speed = barrierSpeed()
        val barrierWidth = barrierWidth()
        val clearanceHeight = clearanceHeight()

        carVelocity += gravity() * deltaSeconds
        carY += carVelocity * deltaSeconds
        carPitch = (carVelocity / height * PITCH_RESPONSE).coerceIn(-20f, 24f)

        barriers = barriers
            .map { barrier -> barrier.copy(x = barrier.x - speed * deltaSeconds) }
            .filter { barrier -> barrier.x + barrierWidth > -dp(28f) }
            .toMutableList()

        barrierTimer += deltaSeconds
        if (barrierTimer >= barrierInterval()) {
            barrierTimer = 0f
            addBarrier()
        }

        val (updatedBarriers, gainedScore) = markClearedBarriers(carX, barriers, barrierWidth)
        barriers = updatedBarriers.toMutableList()
        score += gainedScore

        val bounds = CollisionBounds(
            barrierWidth = barrierWidth,
            clearanceHeight = clearanceHeight,
            worldHeight = height.toFloat(),
            roadTop = roadTop,
        )
        val hitWorldEdge = carY - radius < 0f || carY + radius > roadTop
        if (hitWorldEdge || vehicleHitsBarrier(carX, carY, radius * COLLISION_RADIUS_RATIO, barriers, bounds)) {
            endGame()
        }
    }

    private fun addBarrier() {
        val roadTop = roadTop()
        val clearanceHeight = clearanceHeight()
        val minCenter = clearanceHeight / 2f + dp(58f)
        val maxCenter = roadTop - clearanceHeight / 2f - dp(54f)
        val centerY = if (maxCenter > minCenter) {
            random.nextDouble(minCenter.toDouble(), maxCenter.toDouble()).toFloat()
        } else {
            roadTop * 0.5f
        }
        barriers += FlightBarrier(x = width + barrierWidth(), clearanceCenterY = centerY)
    }

    private fun endGame() {
        screen = Screen.Crash
        if (score > highScore) {
            highScore = score
            prefs.edit().putInt(KEY_HIGH_SCORE, highScore).apply()
        }
    }

    private fun drawGarage(canvas: Canvas) {
        drawCityBackground(canvas)
        drawGarageFloor(canvas)

        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.menu_title),
            centerX = width / 2f,
            baselineY = height * 0.17f,
            maxWidth = width - dp(40f),
            preferredSize = dp(43f),
            color = Color.WHITE,
            bold = true,
        )
        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.menu_subtitle),
            centerX = width / 2f,
            baselineY = height * 0.235f,
            maxWidth = width - dp(44f),
            preferredSize = dp(15.5f),
            color = Color.rgb(226, 242, 245),
            bold = false,
        )

        carButtons.forEach { (car, rect) ->
            drawCarChoice(canvas, car, rect, car == selectedCar)
        }

        drawButton(
            canvas = canvas,
            rect = playButton,
            text = resources.getString(R.string.play),
            fillColor = Color.rgb(255, 203, 71),
            textColor = Color.rgb(25, 31, 36),
        )

        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.best_score, highScore),
            centerX = width / 2f,
            baselineY = playButton.bottom + dp(42f),
            maxWidth = width - dp(40f),
            preferredSize = dp(18f),
            color = Color.WHITE,
            bold = true,
        )
    }

    private fun drawFlight(canvas: Canvas, showHint: Boolean) {
        drawCityBackground(canvas)
        drawBarriers(canvas)
        drawFlyingCar(canvas, carX, carY, vehicleRadius(), selectedCar, carPitch)
        drawScore(canvas)

        if (showHint) {
            drawTextFit(
                canvas = canvas,
                text = resources.getString(R.string.tap_to_fly),
                centerX = width / 2f,
                baselineY = height * 0.77f,
                maxWidth = width - dp(32f),
                preferredSize = dp(16f),
                color = Color.WHITE,
                bold = true,
            )
        }
    }

    private fun drawCrashOverlay(canvas: Canvas) {
        fillPaint.shader = null
        fillPaint.color = Color.argb(178, 8, 12, 18)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.game_over),
            centerX = width / 2f,
            baselineY = height * 0.34f,
            maxWidth = width - dp(40f),
            preferredSize = dp(36f),
            color = Color.WHITE,
            bold = true,
        )
        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.score, score),
            centerX = width / 2f,
            baselineY = height * 0.41f,
            maxWidth = width - dp(40f),
            preferredSize = dp(22f),
            color = Color.rgb(255, 203, 71),
            bold = true,
        )
        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.best_score, highScore),
            centerX = width / 2f,
            baselineY = height * 0.46f,
            maxWidth = width - dp(40f),
            preferredSize = dp(18f),
            color = Color.WHITE,
            bold = false,
        )

        drawButton(
            canvas = canvas,
            rect = retryButton,
            text = resources.getString(R.string.try_again),
            fillColor = Color.rgb(255, 203, 71),
            textColor = Color.rgb(25, 31, 36),
        )
        drawButton(
            canvas = canvas,
            rect = garageButton,
            text = resources.getString(R.string.garage),
            fillColor = Color.rgb(27, 142, 124),
            textColor = Color.WHITE,
        )
    }

    private fun drawCityBackground(canvas: Canvas) {
        val roadTop = roadTop()
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            roadTop,
            intArrayOf(
                Color.rgb(39, 72, 112),
                Color.rgb(87, 160, 190),
                Color.rgb(181, 224, 216),
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), roadTop, fillPaint)
        fillPaint.shader = null

        drawCloudBands(canvas, roadTop)
        drawSkyline(canvas, roadTop)
        drawRoad(canvas, roadTop)
    }

    private fun drawCloudBands(canvas: Canvas, roadTop: Float) {
        fillPaint.color = Color.argb(78, 255, 255, 255)
        val cloudRadius = dp(18f)
        val drift = (elapsedSeconds * dp(18f)) % (width + dp(160f))
        val cloudY = roadTop * 0.18f
        var x = -dp(140f) - drift
        while (x < width + dp(180f)) {
            canvas.drawCircle(x, cloudY, cloudRadius, fillPaint)
            canvas.drawCircle(x + dp(22f), cloudY - dp(8f), cloudRadius * 1.25f, fillPaint)
            canvas.drawCircle(x + dp(48f), cloudY, cloudRadius * 0.95f, fillPaint)
            x += dp(172f)
        }
    }

    private fun drawSkyline(canvas: Canvas, roadTop: Float) {
        val base = roadTop - dp(92f)
        val widths = floatArrayOf(42f, 56f, 38f, 64f, 48f, 72f)
        val heights = floatArrayOf(82f, 116f, 66f, 138f, 94f, 124f)
        var x = -dp(12f)
        var index = 0
        while (x < width + dp(72f)) {
            val buildingWidth = dp(widths[index % widths.size])
            val buildingHeight = dp(heights[index % heights.size])
            val top = base - buildingHeight + (index % 3) * dp(10f)
            fillPaint.color = if (index % 2 == 0) Color.rgb(40, 59, 78) else Color.rgb(48, 68, 90)
            canvas.drawRect(x, top, x + buildingWidth, roadTop, fillPaint)

            fillPaint.color = Color.argb(128, 255, 219, 118)
            val windowSize = dp(5f)
            var wx = x + dp(9f)
            while (wx < x + buildingWidth - dp(7f)) {
                var wy = top + dp(16f)
                while (wy < roadTop - dp(18f)) {
                    if (((wx + wy).toInt() / 7 + index) % 3 != 0) {
                        canvas.drawRoundRect(wx, wy, wx + windowSize, wy + windowSize, dp(1f), dp(1f), fillPaint)
                    }
                    wy += dp(17f)
                }
                wx += dp(15f)
            }

            x += buildingWidth + dp(5f)
            index += 1
        }
    }

    private fun drawRoad(canvas: Canvas, roadTop: Float) {
        fillPaint.color = Color.rgb(37, 42, 48)
        canvas.drawRect(0f, roadTop, width.toFloat(), height.toFloat(), fillPaint)

        fillPaint.color = Color.rgb(49, 55, 62)
        val laneHeight = (height - roadTop) / 3f
        canvas.drawRect(0f, roadTop + laneHeight, width.toFloat(), roadTop + laneHeight * 2f, fillPaint)

        strokePaint.color = Color.argb(190, 255, 216, 96)
        strokePaint.strokeWidth = dp(3f)
        strokePaint.pathEffect = null
        val dashWidth = dp(34f)
        val dashGap = dp(24f)
        val offset = (elapsedSeconds * dp(80f)) % (dashWidth + dashGap)
        var x = -offset
        val laneY = roadTop + laneHeight
        while (x < width) {
            canvas.drawLine(x, laneY, min(x + dashWidth, width.toFloat()), laneY, strokePaint)
            canvas.drawLine(x, roadTop + laneHeight * 2f, min(x + dashWidth, width.toFloat()), roadTop + laneHeight * 2f, strokePaint)
            x += dashWidth + dashGap
        }

        fillPaint.color = Color.rgb(23, 28, 33)
        canvas.drawRect(0f, roadTop - dp(7f), width.toFloat(), roadTop + dp(2f), fillPaint)
    }

    private fun drawGarageFloor(canvas: Canvas) {
        val floorTop = height * 0.59f
        fillPaint.shader = LinearGradient(
            0f,
            floorTop,
            0f,
            height.toFloat(),
            Color.rgb(35, 43, 50),
            Color.rgb(20, 25, 30),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, floorTop, width.toFloat(), height.toFloat(), fillPaint)
        fillPaint.shader = null

        strokePaint.color = Color.argb(68, 255, 255, 255)
        strokePaint.strokeWidth = dp(1f)
        var y = floorTop + dp(20f)
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, strokePaint)
            y += dp(30f)
        }
    }

    private fun drawBarriers(canvas: Canvas) {
        val bounds = CollisionBounds(
            barrierWidth = barrierWidth(),
            clearanceHeight = clearanceHeight(),
            worldHeight = height.toFloat(),
            roadTop = roadTop(),
        )
        barriers.forEach { barrier ->
            drawTrafficBarrier(canvas, barrier.topBarrierRect(bounds).toRectF(), isTop = true)
            drawTrafficBarrier(canvas, barrier.bottomBarrierRect(bounds).toRectF(), isTop = false)
        }
    }

    private fun drawTrafficBarrier(canvas: Canvas, rect: RectF, isTop: Boolean) {
        if (rect.height() <= dp(18f)) return

        fillPaint.shader = null
        fillPaint.color = Color.argb(72, 0, 0, 0)
        canvas.drawRoundRect(
            rect.left + dp(5f),
            rect.top + dp(5f),
            rect.right + dp(5f),
            rect.bottom + dp(5f),
            dp(13f),
            dp(13f),
            fillPaint,
        )

        fillPaint.color = Color.rgb(34, 42, 48)
        canvas.drawRoundRect(rect, dp(13f), dp(13f), fillPaint)

        val inset = dp(8f)
        val panel = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        fillPaint.color = Color.rgb(239, 103, 50)
        canvas.drawRoundRect(panel, dp(8f), dp(8f), fillPaint)

        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = dp(7f)
        canvas.save()
        canvas.clipRect(panel)
        var stripeX = panel.left - panel.height()
        while (stripeX < panel.right + panel.height()) {
            canvas.drawLine(stripeX, panel.bottom, stripeX + panel.height(), panel.top, strokePaint)
            stripeX += dp(30f)
        }
        canvas.restore()

        fillPaint.color = Color.rgb(28, 119, 112)
        val capHeight = dp(18f)
        val cap = if (isTop) {
            RectF(rect.left - dp(5f), rect.bottom - capHeight, rect.right + dp(5f), rect.bottom + dp(3f))
        } else {
            RectF(rect.left - dp(5f), rect.top - dp(3f), rect.right + dp(5f), rect.top + capHeight)
        }
        canvas.drawRoundRect(cap, dp(10f), dp(10f), fillPaint)

        fillPaint.color = Color.rgb(255, 203, 71)
        val lightRadius = dp(4.5f)
        val lightY = if (isTop) cap.centerY() else cap.centerY()
        canvas.drawCircle(cap.left + dp(18f), lightY, lightRadius, fillPaint)
        canvas.drawCircle(cap.right - dp(18f), lightY, lightRadius, fillPaint)
    }

    private fun drawScore(canvas: Canvas) {
        drawTextFit(
            canvas = canvas,
            text = score.toString(),
            centerX = width / 2f,
            baselineY = dp(64f),
            maxWidth = width - dp(40f),
            preferredSize = dp(44f),
            color = Color.WHITE,
            bold = true,
        )
    }

    private fun drawCarChoice(canvas: Canvas, car: CarSkin, rect: RectF, selected: Boolean) {
        fillPaint.shader = null
        fillPaint.color = if (selected) Color.rgb(255, 203, 71) else Color.argb(158, 22, 34, 43)
        canvas.drawRoundRect(rect, dp(14f), dp(14f), fillPaint)

        strokePaint.color = if (selected) Color.WHITE else Color.argb(150, 255, 255, 255)
        strokePaint.strokeWidth = if (selected) dp(3f) else dp(1.5f)
        canvas.drawRoundRect(rect, dp(14f), dp(14f), strokePaint)

        drawFlyingCar(
            canvas = canvas,
            centerX = rect.centerX(),
            centerY = rect.top + rect.height() * 0.42f,
            radius = min(rect.width(), rect.height()) * 0.2f,
            skin = car,
            pitch = 0f,
        )
        drawTextFit(
            canvas = canvas,
            text = resources.getString(car.titleRes),
            centerX = rect.centerX(),
            baselineY = rect.bottom - dp(15f),
            maxWidth = rect.width() - dp(8f),
            preferredSize = dp(12.5f),
            color = if (selected) Color.rgb(25, 31, 36) else Color.WHITE,
            bold = true,
        )
    }

    private fun drawButton(
        canvas: Canvas,
        rect: RectF,
        text: String,
        fillColor: Int,
        textColor: Int,
    ) {
        fillPaint.shader = null
        fillPaint.color = Color.argb(82, 0, 0, 0)
        canvas.drawRoundRect(rect.left, rect.top + dp(5f), rect.right, rect.bottom + dp(5f), dp(16f), dp(16f), fillPaint)

        fillPaint.color = fillColor
        canvas.drawRoundRect(rect, dp(16f), dp(16f), fillPaint)

        drawTextFit(
            canvas = canvas,
            text = text,
            centerX = rect.centerX(),
            baselineY = textBaselineForCenter(rect.centerY(), dp(21f)),
            maxWidth = rect.width() - dp(24f),
            preferredSize = dp(21f),
            color = textColor,
            bold = true,
        )
    }

    private fun drawFlyingCar(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        skin: CarSkin,
        pitch: Float,
    ) {
        canvas.save()
        canvas.rotate(pitch, centerX, centerY)

        fillPaint.shader = null
        fillPaint.color = Color.argb(80, 0, 0, 0)
        canvas.drawOval(
            centerX - radius * 1.25f,
            centerY + radius * 0.58f,
            centerX + radius * 1.28f,
            centerY + radius * 1.04f,
            fillPaint,
        )

        drawWing(canvas, centerX, centerY, radius, left = true, skin.accentColor)
        drawWing(canvas, centerX, centerY, radius, left = false, skin.accentColor)

        val body = RectF(
            centerX - radius * 1.22f,
            centerY - radius * 0.42f,
            centerX + radius * 1.24f,
            centerY + radius * 0.48f,
        )
        fillPaint.color = skin.bodyColor
        canvas.drawRoundRect(body, radius * 0.34f, radius * 0.34f, fillPaint)

        fillPaint.color = skin.roofColor
        val cabin = Path().apply {
            moveTo(centerX - radius * 0.48f, centerY - radius * 0.42f)
            lineTo(centerX - radius * 0.2f, centerY - radius * 0.88f)
            lineTo(centerX + radius * 0.5f, centerY - radius * 0.88f)
            lineTo(centerX + radius * 0.82f, centerY - radius * 0.42f)
            close()
        }
        canvas.drawPath(cabin, fillPaint)

        fillPaint.color = Color.argb(210, 145, 220, 232)
        canvas.drawRoundRect(
            centerX - radius * 0.18f,
            centerY - radius * 0.78f,
            centerX + radius * 0.44f,
            centerY - radius * 0.48f,
            radius * 0.08f,
            radius * 0.08f,
            fillPaint,
        )

        fillPaint.color = Color.rgb(23, 27, 31)
        canvas.drawCircle(centerX - radius * 0.72f, centerY + radius * 0.47f, radius * 0.22f, fillPaint)
        canvas.drawCircle(centerX + radius * 0.72f, centerY + radius * 0.47f, radius * 0.22f, fillPaint)

        fillPaint.color = Color.rgb(232, 238, 242)
        canvas.drawCircle(centerX - radius * 0.72f, centerY + radius * 0.47f, radius * 0.1f, fillPaint)
        canvas.drawCircle(centerX + radius * 0.72f, centerY + radius * 0.47f, radius * 0.1f, fillPaint)

        fillPaint.color = Color.rgb(255, 243, 165)
        canvas.drawCircle(centerX + radius * 1.08f, centerY - radius * 0.02f, radius * 0.1f, fillPaint)

        if (abs(pitch) > 1f) {
            fillPaint.color = Color.argb(120, 255, 229, 138)
            canvas.drawOval(
                centerX - radius * 1.68f,
                centerY - radius * 0.08f,
                centerX - radius * 1.12f,
                centerY + radius * 0.28f,
                fillPaint,
            )
        }

        canvas.restore()
    }

    private fun drawWing(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        left: Boolean,
        color: Int,
    ) {
        val direction = if (left) -1f else 1f
        fillPaint.color = color
        val wing = Path().apply {
            moveTo(centerX + direction * radius * 0.18f, centerY + radius * 0.05f)
            lineTo(centerX + direction * radius * 1.12f, centerY - radius * 0.3f)
            lineTo(centerX + direction * radius * 0.86f, centerY + radius * 0.28f)
            close()
        }
        canvas.drawPath(wing, fillPaint)
    }

    private fun drawTextFit(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        maxWidth: Float,
        preferredSize: Float,
        color: Int,
        bold: Boolean,
    ) {
        textPaint.color = color
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        textPaint.textSize = preferredSize
        val measured = textPaint.measureText(text)
        if (measured > maxWidth) {
            textPaint.textSize = max(dp(10f), preferredSize * (maxWidth / measured))
        }
        canvas.drawText(text, centerX, baselineY, textPaint)
    }

    private fun layoutControls(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return

        val margin = dp(18f)
        val gap = dp(8f)
        val selectorWidth = (width - margin * 2f - gap * 2f) / 3f
        val selectorHeight = min(dp(116f), height * 0.165f)
        val selectorTop = height * 0.39f

        CarSkin.entries.forEachIndexed { index, car ->
            val left = margin + index * (selectorWidth + gap)
            carButtons[car] = RectF(left, selectorTop, left + selectorWidth, selectorTop + selectorHeight)
        }

        val buttonWidth = min(width - margin * 2f, dp(236f))
        val buttonHeight = dp(58f)
        playButton.set(
            (width - buttonWidth) / 2f,
            height * 0.68f,
            (width + buttonWidth) / 2f,
            height * 0.68f + buttonHeight,
        )

        retryButton.set(
            (width - buttonWidth) / 2f,
            height * 0.54f,
            (width + buttonWidth) / 2f,
            height * 0.54f + buttonHeight,
        )
        garageButton.set(
            (width - buttonWidth) / 2f,
            retryButton.bottom + dp(14f),
            (width + buttonWidth) / 2f,
            retryButton.bottom + dp(14f) + buttonHeight,
        )
    }

    private fun textBaselineForCenter(centerY: Float, textSize: Float): Float {
        textPaint.textSize = textSize
        val metrics = textPaint.fontMetrics
        return centerY - (metrics.ascent + metrics.descent) / 2f
    }

    private fun roadTop(): Float = height * ROAD_TOP_RATIO

    private fun vehicleRadius(): Float = min(width, height) * VEHICLE_RADIUS_RATIO

    private fun barrierWidth(): Float = max(dp(64f), width * BARRIER_WIDTH_RATIO)

    private fun clearanceHeight(): Float {
        val base = height * CLEARANCE_HEIGHT_RATIO
        val tightening = min(score * dp(1.1f), dp(36f))
        return max(dp(166f), base - tightening)
    }

    private fun gravity(): Float = height * GRAVITY_RATIO

    private fun barrierSpeed(): Float = width * SPEED_RATIO + score * dp(2.1f)

    private fun barrierInterval(): Float = max(1.06f, 1.5f - score * 0.012f)

    private fun dp(value: Float): Float = value * density

    private fun GameRect.toRectF(): RectF = RectF(left, top, right, bottom)

    private companion object {
        private const val KEY_HIGH_SCORE = "high_score"
        private const val KEY_SELECTED_CAR = "selected_car"
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val MAX_FRAME_DELTA_SECONDS = 0.033f
        private const val CAR_X_RATIO = 0.28f
        private const val START_Y_RATIO = 0.42f
        private const val ROAD_TOP_RATIO = 0.86f
        private const val VEHICLE_RADIUS_RATIO = 0.047f
        private const val BARRIER_WIDTH_RATIO = 0.15f
        private const val CLEARANCE_HEIGHT_RATIO = 0.255f
        private const val GRAVITY_RATIO = 1.66f
        private const val BOOST_STRENGTH_RATIO = 0.57f
        private const val SPEED_RATIO = 0.46f
        private const val PITCH_RESPONSE = 60f
        private const val COLLISION_RADIUS_RATIO = 0.84f
    }
}
