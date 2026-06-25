package com.footballbird.app

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
import com.footballbird.app.game.CollisionBounds
import com.footballbird.app.game.GameRect
import com.footballbird.app.game.Pipe
import com.footballbird.app.game.bottomRect
import com.footballbird.app.game.hitsObstacle
import com.footballbird.app.game.topRect
import com.footballbird.app.game.updatePassedPipes
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class FootballBirdView(context: Context) : View(context), Choreographer.FrameCallback {
    private enum class Screen {
        Menu,
        Playing,
        GameOver,
    }

    private enum class BallType(
        val storageKey: String,
        val titleRes: Int,
    ) {
        Football("football", R.string.football),
        Volleyball("volleyball", R.string.volleyball),
        Basketball("basketball", R.string.basketball),
    }

    private val prefs = context.getSharedPreferences("football_bird", Context.MODE_PRIVATE)
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

    private var screen = Screen.Menu
    private var selectedBall = BallType.entries.firstOrNull {
        it.storageKey == prefs.getString(KEY_SELECTED_BALL, BallType.Football.storageKey)
    } ?: BallType.Football

    private var highScore = prefs.getInt(KEY_HIGH_SCORE, 0)
    private var score = 0
    private var ballX = 0f
    private var ballY = 0f
    private var ballVelocity = 0f
    private var ballRotation = 0f
    private var elapsed = 0f
    private var spawnTimer = 0f
    private var lastFrameTimeNs = 0L
    private var isRunning = false
    private var pipes = mutableListOf<Pipe>()

    private val ballButtons = mutableMapOf<BallType, RectF>()
    private val playButton = RectF()
    private val retryButton = RectF()
    private val menuButton = RectF()

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
            if (screen == Screen.Playing) updateGame(deltaSeconds)
        }

        lastFrameTimeNs = frameTimeNanos
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        layoutControls(width.toFloat(), height.toFloat())
        if (ballX == 0f) resetBall()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true

        when (screen) {
            Screen.Menu -> handleMenuTap(event.x, event.y)
            Screen.Playing -> flap()
            Screen.GameOver -> handleGameOverTap(event.x, event.y)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        layoutControls(width.toFloat(), height.toFloat())

        when (screen) {
            Screen.Menu -> drawMenu(canvas)
            Screen.Playing -> drawGame(canvas, showHint = score == 0 && elapsed < 3.2f)
            Screen.GameOver -> {
                drawGame(canvas, showHint = false)
                drawGameOver(canvas)
            }
        }
    }

    private fun handleMenuTap(x: Float, y: Float) {
        val tappedBall = ballButtons.entries.firstOrNull { (_, rect) -> rect.contains(x, y) }?.key
        if (tappedBall != null) {
            selectedBall = tappedBall
            prefs.edit().putString(KEY_SELECTED_BALL, selectedBall.storageKey).apply()
            invalidate()
            return
        }

        if (playButton.contains(x, y)) {
            startGame()
        }
    }

    private fun handleGameOverTap(x: Float, y: Float) {
        when {
            retryButton.contains(x, y) -> startGame()
            menuButton.contains(x, y) -> {
                screen = Screen.Menu
                resetBall()
                invalidate()
            }
            else -> startGame()
        }
    }

    private fun startGame() {
        score = 0
        elapsed = 0f
        spawnTimer = 0f
        pipes.clear()
        resetBall()
        screen = Screen.Playing
        invalidate()
    }

    private fun resetBall() {
        ballX = width * BALL_X_RATIO
        ballY = height * START_Y_RATIO
        ballVelocity = 0f
        ballRotation = 0f
    }

    private fun flap() {
        ballVelocity = -height * FLAP_STRENGTH_RATIO
    }

    private fun updateGame(deltaSeconds: Float) {
        if (width == 0 || height == 0) return

        elapsed += deltaSeconds
        val groundTop = groundTop()
        val radius = ballRadius()
        val speed = obstacleSpeed()
        val pipeWidth = pipeWidth()
        val gapHeight = gapHeight()

        ballVelocity += gravity() * deltaSeconds
        ballY += ballVelocity * deltaSeconds
        ballRotation += speed * deltaSeconds * ROTATION_SPEED

        pipes = pipes
            .map { pipe -> pipe.copy(x = pipe.x - speed * deltaSeconds) }
            .filter { pipe -> pipe.x + pipeWidth > -dp(24f) }
            .toMutableList()

        spawnTimer += deltaSeconds
        if (spawnTimer >= spawnInterval()) {
            spawnTimer = 0f
            addPipe()
        }

        val (updatedPipes, gainedScore) = updatePassedPipes(ballX, pipes, pipeWidth)
        pipes = updatedPipes.toMutableList()
        score += gainedScore

        val bounds = CollisionBounds(
            pipeWidth = pipeWidth,
            gapHeight = gapHeight,
            worldHeight = height.toFloat(),
            groundTop = groundTop,
        )
        val hitWorldEdge = ballY - radius < 0f || ballY + radius > groundTop
        if (hitWorldEdge || hitsObstacle(ballX, ballY, radius * COLLISION_RADIUS_RATIO, pipes, bounds)) {
            endGame()
        }
    }

    private fun addPipe() {
        val groundTop = groundTop()
        val gapHeight = gapHeight()
        val minCenter = gapHeight / 2f + dp(44f)
        val maxCenter = groundTop - gapHeight / 2f - dp(48f)
        val centerY = if (maxCenter > minCenter) {
            random.nextDouble(minCenter.toDouble(), maxCenter.toDouble()).toFloat()
        } else {
            groundTop * 0.5f
        }
        pipes += Pipe(x = width + pipeWidth(), gapCenterY = centerY)
    }

    private fun endGame() {
        screen = Screen.GameOver
        if (score > highScore) {
            highScore = score
            prefs.edit().putInt(KEY_HIGH_SCORE, highScore).apply()
        }
    }

    private fun drawMenu(canvas: Canvas) {
        drawBackground(canvas)

        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.menu_title),
            centerX = width / 2f,
            baselineY = height * 0.18f,
            maxWidth = width - dp(40f),
            preferredSize = dp(42f),
            color = Color.WHITE,
            bold = true,
        )
        drawTextFit(
            canvas = canvas,
            text = resources.getString(R.string.menu_subtitle),
            centerX = width / 2f,
            baselineY = height * 0.24f,
            maxWidth = width - dp(44f),
            preferredSize = dp(16f),
            color = Color.rgb(224, 246, 235),
            bold = false,
        )

        ballButtons.forEach { (ball, rect) ->
            drawBallChoice(canvas, ball, rect, ball == selectedBall)
        }

        drawButton(
            canvas = canvas,
            rect = playButton,
            text = resources.getString(R.string.play),
            fillColor = Color.rgb(255, 210, 64),
            textColor = Color.rgb(20, 30, 34),
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

    private fun drawGame(canvas: Canvas, showHint: Boolean) {
        drawBackground(canvas)
        drawPipes(canvas)
        drawBall(canvas, ballX, ballY, ballRadius(), selectedBall, ballRotation)
        drawScore(canvas)

        if (showHint) {
            drawTextFit(
                canvas = canvas,
                text = resources.getString(R.string.tap_to_fly),
                centerX = width / 2f,
                baselineY = height * 0.78f,
                maxWidth = width - dp(32f),
                preferredSize = dp(16f),
                color = Color.WHITE,
                bold = true,
            )
        }
    }

    private fun drawGameOver(canvas: Canvas) {
        fillPaint.shader = null
        fillPaint.color = Color.argb(178, 0, 0, 0)
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
            color = Color.rgb(255, 230, 90),
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
            fillColor = Color.rgb(255, 210, 64),
            textColor = Color.rgb(20, 30, 34),
        )
        drawButton(
            canvas = canvas,
            rect = menuButton,
            text = resources.getString(R.string.menu),
            fillColor = Color.rgb(31, 150, 88),
            textColor = Color.WHITE,
        )
    }

    private fun drawBackground(canvas: Canvas) {
        val skyBottom = groundTop()
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            skyBottom,
            intArrayOf(
                Color.rgb(58, 161, 232),
                Color.rgb(106, 205, 239),
                Color.rgb(193, 238, 231),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), skyBottom, fillPaint)
        fillPaint.shader = null

        drawStadium(canvas, skyBottom)
        drawField(canvas, skyBottom)
    }

    private fun drawStadium(canvas: Canvas, groundTop: Float) {
        val standTop = groundTop - dp(120f)
        fillPaint.color = Color.rgb(36, 75, 80)
        canvas.drawRect(0f, standTop, width.toFloat(), groundTop, fillPaint)

        val rowHeight = dp(18f)
        val colors = intArrayOf(
            Color.rgb(245, 245, 245),
            Color.rgb(37, 171, 94),
            Color.rgb(255, 210, 64),
            Color.rgb(226, 72, 63),
        )
        var y = standTop + dp(10f)
        var row = 0
        while (y < groundTop - dp(14f)) {
            fillPaint.color = colors[row % colors.size]
            var x = -((row % 2) * dp(20f))
            while (x < width + dp(36f)) {
                canvas.drawRoundRect(
                    x,
                    y,
                    x + dp(28f),
                    y + rowHeight,
                    dp(4f),
                    dp(4f),
                    fillPaint,
                )
                x += dp(40f)
            }
            y += rowHeight + dp(8f)
            row += 1
        }
    }

    private fun drawField(canvas: Canvas, groundTop: Float) {
        fillPaint.color = Color.rgb(37, 154, 70)
        canvas.drawRect(0f, groundTop, width.toFloat(), height.toFloat(), fillPaint)

        val stripeWidth = dp(58f)
        var x = -stripeWidth
        var index = 0
        while (x < width + stripeWidth) {
            fillPaint.color = if (index % 2 == 0) {
                Color.rgb(45, 176, 80)
            } else {
                Color.rgb(33, 136, 63)
            }
            val path = Path().apply {
                moveTo(x, groundTop)
                lineTo(x + stripeWidth, groundTop)
                lineTo(x + stripeWidth * 1.5f, height.toFloat())
                lineTo(x + stripeWidth * 0.5f, height.toFloat())
                close()
            }
            canvas.drawPath(path, fillPaint)
            x += stripeWidth
            index += 1
        }

        strokePaint.color = Color.argb(145, 255, 255, 255)
        strokePaint.strokeWidth = dp(2f)
        canvas.drawLine(0f, groundTop + dp(22f), width.toFloat(), groundTop + dp(22f), strokePaint)
    }

    private fun drawPipes(canvas: Canvas) {
        val bounds = CollisionBounds(
            pipeWidth = pipeWidth(),
            gapHeight = gapHeight(),
            worldHeight = height.toFloat(),
            groundTop = groundTop(),
        )
        pipes.forEach { pipe ->
            drawGoalObstacle(canvas, pipe.topRect(bounds).toRectF(), isTop = true)
            drawGoalObstacle(canvas, pipe.bottomRect(bounds).toRectF(), isTop = false)
        }
    }

    private fun drawGoalObstacle(canvas: Canvas, rect: RectF, isTop: Boolean) {
        if (rect.height() <= dp(18f)) return

        fillPaint.shader = null
        fillPaint.color = Color.argb(65, 0, 0, 0)
        canvas.drawRoundRect(rect.left + dp(5f), rect.top + dp(5f), rect.right + dp(5f), rect.bottom + dp(5f), dp(12f), dp(12f), fillPaint)

        fillPaint.color = Color.rgb(247, 248, 240)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), fillPaint)

        fillPaint.color = Color.rgb(230, 237, 226)
        val inner = RectF(rect.left + dp(8f), rect.top + dp(8f), rect.right - dp(8f), rect.bottom - dp(8f))
        canvas.drawRoundRect(inner, dp(8f), dp(8f), fillPaint)

        strokePaint.color = Color.argb(120, 35, 78, 88)
        strokePaint.strokeWidth = dp(1.2f)
        canvas.save()
        canvas.clipRect(inner)
        var x = inner.left
        while (x <= inner.right) {
            canvas.drawLine(x, inner.top, x + inner.height() * 0.4f, inner.bottom, strokePaint)
            x += dp(18f)
        }
        var y = inner.top
        while (y <= inner.bottom) {
            canvas.drawLine(inner.left, y, inner.right, y, strokePaint)
            y += dp(18f)
        }
        canvas.restore()

        fillPaint.color = Color.rgb(26, 150, 83)
        val capHeight = dp(18f)
        val cap = if (isTop) {
            RectF(rect.left - dp(6f), rect.bottom - capHeight, rect.right + dp(6f), rect.bottom + dp(4f))
        } else {
            RectF(rect.left - dp(6f), rect.top - dp(4f), rect.right + dp(6f), rect.top + capHeight)
        }
        canvas.drawRoundRect(cap, dp(10f), dp(10f), fillPaint)
    }

    private fun drawScore(canvas: Canvas) {
        drawTextFit(
            canvas = canvas,
            text = score.toString(),
            centerX = width / 2f,
            baselineY = dp(64f),
            maxWidth = width - dp(40f),
            preferredSize = dp(42f),
            color = Color.WHITE,
            bold = true,
        )
    }

    private fun drawBallChoice(canvas: Canvas, ball: BallType, rect: RectF, selected: Boolean) {
        fillPaint.shader = null
        fillPaint.color = if (selected) Color.rgb(255, 210, 64) else Color.argb(150, 12, 44, 47)
        canvas.drawRoundRect(rect, dp(18f), dp(18f), fillPaint)

        strokePaint.color = if (selected) Color.WHITE else Color.argb(150, 255, 255, 255)
        strokePaint.strokeWidth = if (selected) dp(3f) else dp(1.5f)
        canvas.drawRoundRect(rect, dp(18f), dp(18f), strokePaint)

        drawBall(
            canvas = canvas,
            centerX = rect.centerX(),
            centerY = rect.top + rect.height() * 0.42f,
            radius = min(rect.width(), rect.height()) * 0.22f,
            type = ball,
            rotation = 0f,
        )
        drawTextFit(
            canvas = canvas,
            text = resources.getString(ball.titleRes),
            centerX = rect.centerX(),
            baselineY = rect.bottom - dp(15f),
            maxWidth = rect.width() - dp(8f),
            preferredSize = dp(12.5f),
            color = if (selected) Color.rgb(20, 30, 34) else Color.WHITE,
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
        fillPaint.color = Color.argb(85, 0, 0, 0)
        canvas.drawRoundRect(rect.left, rect.top + dp(5f), rect.right, rect.bottom + dp(5f), dp(18f), dp(18f), fillPaint)

        fillPaint.color = fillColor
        canvas.drawRoundRect(rect, dp(18f), dp(18f), fillPaint)

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

    private fun drawBall(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        type: BallType,
        rotation: Float,
    ) {
        fillPaint.shader = null
        fillPaint.color = Color.argb(85, 0, 0, 0)
        canvas.drawCircle(centerX + radius * 0.18f, centerY + radius * 0.24f, radius * 0.96f, fillPaint)

        canvas.save()
        canvas.rotate(rotation, centerX, centerY)
        when (type) {
            BallType.Football -> drawFootball(canvas, centerX, centerY, radius)
            BallType.Volleyball -> drawVolleyball(canvas, centerX, centerY, radius)
            BallType.Basketball -> drawBasketball(canvas, centerX, centerY, radius)
        }
        canvas.restore()
    }

    private fun drawFootball(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        fillPaint.color = Color.WHITE
        canvas.drawCircle(centerX, centerY, radius, fillPaint)

        strokePaint.color = Color.rgb(24, 28, 30)
        strokePaint.strokeWidth = radius * 0.055f
        canvas.drawCircle(centerX, centerY, radius, strokePaint)

        fillPaint.color = Color.rgb(16, 18, 20)
        drawPolygon(canvas, centerX, centerY, radius * 0.34f, sides = 5, rotation = -90f)
        drawPolygon(canvas, centerX - radius * 0.58f, centerY - radius * 0.12f, radius * 0.22f, sides = 5, rotation = -32f)
        drawPolygon(canvas, centerX + radius * 0.58f, centerY - radius * 0.12f, radius * 0.22f, sides = 5, rotation = 32f)
        drawPolygon(canvas, centerX - radius * 0.28f, centerY + radius * 0.56f, radius * 0.2f, sides = 5, rotation = 18f)
        drawPolygon(canvas, centerX + radius * 0.28f, centerY + radius * 0.56f, radius * 0.2f, sides = 5, rotation = -18f)
    }

    private fun drawVolleyball(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val ballPath = Path().apply {
            addCircle(centerX, centerY, radius, Path.Direction.CW)
        }
        fillPaint.color = Color.WHITE
        canvas.drawCircle(centerX, centerY, radius, fillPaint)

        canvas.save()
        canvas.clipPath(ballPath)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = radius * 0.28f
        strokePaint.color = Color.rgb(31, 115, 210)
        canvas.drawArc(centerX - radius * 1.2f, centerY - radius * 0.95f, centerX + radius * 1.2f, centerY + radius * 0.95f, 205f, 90f, false, strokePaint)
        strokePaint.color = Color.rgb(255, 199, 40)
        canvas.drawArc(centerX - radius * 0.95f, centerY - radius * 1.18f, centerX + radius * 0.95f, centerY + radius * 1.18f, 318f, 90f, false, strokePaint)
        strokePaint.color = Color.rgb(230, 70, 64)
        canvas.drawArc(centerX - radius * 1.08f, centerY - radius * 1.08f, centerX + radius * 1.08f, centerY + radius * 1.08f, 78f, 84f, false, strokePaint)
        canvas.restore()

        strokePaint.color = Color.rgb(32, 40, 44)
        strokePaint.strokeWidth = radius * 0.055f
        canvas.drawCircle(centerX, centerY, radius, strokePaint)
    }

    private fun drawBasketball(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        fillPaint.color = Color.rgb(221, 118, 37)
        canvas.drawCircle(centerX, centerY, radius, fillPaint)

        strokePaint.color = Color.rgb(39, 27, 22)
        strokePaint.strokeWidth = radius * 0.07f
        canvas.drawCircle(centerX, centerY, radius, strokePaint)
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, strokePaint)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, strokePaint)
        canvas.drawArc(centerX - radius * 1.45f, centerY - radius, centerX + radius * 0.15f, centerY + radius, -70f, 140f, false, strokePaint)
        canvas.drawArc(centerX - radius * 0.15f, centerY - radius, centerX + radius * 1.45f, centerY + radius, 110f, 140f, false, strokePaint)
    }

    private fun drawPolygon(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        sides: Int,
        rotation: Float,
    ) {
        val path = Path()
        for (index in 0 until sides) {
            val angle = Math.toRadians((rotation + index * 360f / sides).toDouble())
            val x = centerX + cos(angle).toFloat() * radius
            val y = centerY + sin(angle).toFloat() * radius
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, fillPaint)
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
        val selectorHeight = min(dp(118f), height * 0.17f)
        val selectorTop = height * 0.39f

        BallType.entries.forEachIndexed { index, ball ->
            val left = margin + index * (selectorWidth + gap)
            ballButtons[ball] = RectF(left, selectorTop, left + selectorWidth, selectorTop + selectorHeight)
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
        menuButton.set(
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

    private fun groundTop(): Float = height * GROUND_TOP_RATIO

    private fun ballRadius(): Float = min(width, height) * BALL_RADIUS_RATIO

    private fun pipeWidth(): Float = max(dp(68f), width * PIPE_WIDTH_RATIO)

    private fun gapHeight(): Float {
        val base = height * GAP_HEIGHT_RATIO
        val scoreTightening = min(score * dp(1.15f), dp(38f))
        return max(dp(164f), base - scoreTightening)
    }

    private fun gravity(): Float = height * GRAVITY_RATIO

    private fun obstacleSpeed(): Float = width * SPEED_RATIO + score * dp(2.2f)

    private fun spawnInterval(): Float = max(1.08f, 1.52f - score * 0.012f)

    private fun dp(value: Float): Float = value * density

    private fun GameRect.toRectF(): RectF = RectF(left, top, right, bottom)

    private companion object {
        private const val KEY_HIGH_SCORE = "high_score"
        private const val KEY_SELECTED_BALL = "selected_ball"
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val MAX_FRAME_DELTA_SECONDS = 0.033f
        private const val BALL_X_RATIO = 0.28f
        private const val START_Y_RATIO = 0.43f
        private const val GROUND_TOP_RATIO = 0.86f
        private const val BALL_RADIUS_RATIO = 0.046f
        private const val PIPE_WIDTH_RATIO = 0.16f
        private const val GAP_HEIGHT_RATIO = 0.25f
        private const val GRAVITY_RATIO = 1.68f
        private const val FLAP_STRENGTH_RATIO = 0.58f
        private const val SPEED_RATIO = 0.46f
        private const val ROTATION_SPEED = 0.18f
        private const val COLLISION_RADIUS_RATIO = 0.86f
    }
}
