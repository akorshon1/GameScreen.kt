package com.akorshon.arrowcross.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.akorshon.arrowcross.ads.AdsManager
import com.akorshon.arrowcross.audio.AudioManager
import com.akorshon.arrowcross.data.Direction
import com.akorshon.arrowcross.data.LevelRepository
import com.akorshon.arrowcross.data.ProgressStore
import com.akorshon.arrowcross.engine.ArrowGameEngine
import com.akorshon.arrowcross.engine.GameArrow
import com.akorshon.arrowcross.ui.theme.arrowSkinById
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ============================================================
// CONSTANTS
// ============================================================

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f

// The board is fitted to the area the arrows really occupy (not the
// whole empty grid), so every level - tiny or huge - fills the screen.
private const val FIT_RATIO = 0.96f
private const val BOARD_PADDING_CELLS = 0.75f

// Touch target is measured in SCREEN dp, so dense levels stay tappable.
private const val TOUCH_RADIUS = 0.9f
private const val TOUCH_RADIUS_DP = 22f
private const val MAX_CELL_DP = 56f

private const val LONG_PRESS_MS = 300L
private const val LONG_PRESS_RADIUS_MULTIPLIER = 1.5f

private const val GRID_HINT_MS = 1800L

private val ScreenBackground = Color(0xFFFAF7F0)
private val HintBlue = Color(0xFF6676F2)
private val CollisionRed = Color(0xFFE53935)
private val WaitingGray = Color(0xFF8C7C76)

private const val COINS_PER_LEVEL = 25
private const val COIN_STORE_NAME = "arrow_escape_coin_wallet"

// ============================================================
// COIN WALLET
// ============================================================

private object CoinWallet {

    private const val COINS_KEY = "coins"
    private const val CLAIMED_LEVELS_KEY = "claimed_levels"

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(COIN_STORE_NAME, android.content.Context.MODE_PRIVATE)

    fun getCoins(context: android.content.Context): Int =
        prefs(context).getInt(COINS_KEY, 0)

    /** A level pays only once, so replaying can never farm coins. */
    fun claimLevelReward(context: android.content.Context, level: Int, amount: Int): Boolean {
        val p = prefs(context)
        val claimed = (p.getStringSet(CLAIMED_LEVELS_KEY, emptySet()) ?: emptySet()).toMutableSet()
        if (!claimed.add(level.toString())) return false
        p.edit()
            .putInt(COINS_KEY, p.getInt(COINS_KEY, 0) + amount)
            .putStringSet(CLAIMED_LEVELS_KEY, claimed)
            .apply()
        return true
    }

    /** Used by the optional "watch ad for bonus coins" button. */
    fun addCoins(context: android.content.Context, amount: Int) {
        val p = prefs(context)
        p.edit().putInt(COINS_KEY, p.getInt(COINS_KEY, 0) + amount).apply()
    }
}

// ============================================================
// BOARD VIEW STATE (zoom/pan live here so panning never
// recomposes the whole screen)
// ============================================================

@Stable
private class BoardViewState {
    var zoom by mutableFloatStateOf(MIN_ZOOM)
    var pan by mutableStateOf(Offset.Zero)

    fun reset() {
        zoom = MIN_ZOOM
        pan = Offset.Zero
    }
}

// ============================================================
// GAME SCREEN
// ============================================================

@Composable
fun GameScreen(
    levelNumber: Int,
    levels: LevelRepository,
    progress: ProgressStore,
    audio: AudioManager,
    onBack: () -> Unit,
    onNext: (Int) -> Unit,
    onOpenLevel: (Int) -> Unit
) {

    val loadedLevel = remember(levelNumber) { levels.load(levelNumber) }

    var winMistakes by remember(levelNumber) { mutableIntStateOf(-1) }
    var winCoins by remember(levelNumber) { mutableIntStateOf(0) }
    var bonusClaimed by remember(levelNumber) { mutableStateOf(false) }
    var showHard by remember(levelNumber) { mutableStateOf(levelNumber % 10 == 0) }
    var showLevelPicker by remember { mutableStateOf(false) }
    var showGameOver by remember(levelNumber) { mutableStateOf(false) }
    var showGridHint by remember(levelNumber) { mutableStateOf(false) }

    // 2 free hints per level, afterwards a rewarded ad gives one hint.
    var hintUsesLeft by remember(levelNumber) { mutableIntStateOf(2) }
    var hintArrowId by remember(levelNumber) { mutableStateOf<Int?>(null) }

    var brokenHeart by remember(levelNumber) { mutableIntStateOf(-1) }
    var lastMistakes by remember(levelNumber) { mutableIntStateOf(0) }

    val view = remember(levelNumber) { BoardViewState() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val coinBalance = remember { mutableIntStateOf(CoinWallet.getCoins(context)) }

    audio.soundEnabled = progress.soundEnabled()
    audio.musicEnabled = progress.musicEnabled()
    audio.musicVolume = progress.musicVolume()
    audio.sfxVolume = progress.sfxVolume()

    val arrowColor = arrowSkinById(progress.selectedColorId()).color

    // ========================================================
    // ENGINE
    // ========================================================

    val engine = remember(levelNumber) {

        ArrowGameEngine(
            context = context,
            audio = audio,
            scope = scope,
            vibrationEnabled = { progress.vibrationEnabled() },

            onWin = { mistakes ->

                val earnedStars = (3 - mistakes).coerceIn(1, 3)

                progress.complete(level = levelNumber, earnedStars = earnedStars)
                progress.clearLevelProgress(levelNumber)

                val reward = COINS_PER_LEVEL + earnedStars * 10

                if (CoinWallet.claimLevelReward(context, levelNumber, reward)) {
                    winCoins = reward
                    coinBalance.intValue = CoinWallet.getCoins(context)
                }

                winMistakes = mistakes
            },

            onGameOver = { showGameOver = true }
        )
    }

    // ========================================================
    // LIFE LOSS FEEDBACK (heart animation only; the engine already
    // plays the block sound + vibration on impact)
    // ========================================================

    LaunchedEffect(engine.mistakes) {

        val mistakes = engine.mistakes.coerceAtLeast(0)

        if (mistakes > lastMistakes) {
            brokenHeart = (mistakes - 1).coerceIn(0, 2)
            delay(650L)
            brokenHeart = -1
        }

        lastMistakes = mistakes
    }

    // ========================================================
    // LOAD LEVEL
    // ========================================================

    LaunchedEffect(levelNumber) {

        loadedLevel?.let { engine.load(it) }

        if (progress.inProgressLevel() == levelNumber) {
            engine.restoreProgress(
                removedIds = progress.inProgressRemovedIds()
                    .mapNotNull { it.toIntOrNull() }
                    .toSet(),
                restoredMistakes = progress.inProgressMistakes()
            )
        }

        view.reset()
        winMistakes = -1
        showGameOver = false
        showGridHint = false
        hintArrowId = null
        hintUsesLeft = 2
        brokenHeart = -1
        lastMistakes = engine.mistakes
        showHard = levelNumber % 10 == 0
    }

    // ========================================================
    // AUTO-SAVE MID-LEVEL PROGRESS
    // ========================================================

    val removedArrowIds by remember(engine) {
        derivedStateOf {
            engine.arrows
                .filter { it.status == GameArrow.Status.REMOVED }
                .map { it.id }
                .toSet()
        }
    }

    LaunchedEffect(levelNumber, removedArrowIds, engine.mistakes, winMistakes) {
        if (loadedLevel != null && winMistakes < 0) {
            progress.saveLevelProgress(
                level = levelNumber,
                removedIds = removedArrowIds,
                mistakes = engine.mistakes
            )
        }
    }

    DisposableEffect(engine) {
        onDispose { engine.dispose() }
    }

    BackHandler { onBack() }

    fun restartLevel() {

        progress.clearLevelProgress(levelNumber)

        loadedLevel?.let { engine.load(it) }

        view.reset()
        winMistakes = -1
        winCoins = 0
        showGameOver = false
        showGridHint = false
        hintArrowId = null
        hintUsesLeft = 2
        brokenHeart = -1
        lastMistakes = engine.mistakes
    }

    fun showHintNow(): Boolean {
        val id = engine.findFreeArrowId() ?: return false
        hintArrowId = id
        scope.launch {
            delay(GRID_HINT_MS)
            if (hintArrowId == id) hintArrowId = null
        }
        return true
    }

    val arrivedCount by remember(engine) {
        derivedStateOf { engine.arrows.count { it.status == GameArrow.Status.REMOVED } }
    }

    // ========================================================
    // ROOT
    // ========================================================

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .systemBarsPadding()
            .navigationBarsPadding()
    ) {

        Column(Modifier.fillMaxSize()) {

            GameHeader(
                level = levelNumber,
                lives = (3 - engine.mistakes).coerceIn(0, 3),
                arrived = arrivedCount,
                totalArrows = engine.arrows.size,
                brokenHeart = brokenHeart,
                zoomProvider = { view.zoom },
                onBack = onBack,
                onLevels = { showLevelPicker = true },
                onReset = { restartLevel() },
                onZoomReset = { view.reset() }
            )

            // The board fills the whole area (no side padding) so arrows can
            // slide all the way to the screen edge when they escape.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {

                if (loadedLevel != null) {

                    GameBoard(
                        engine = engine,
                        arrowColor = arrowColor,
                        view = view,
                        showGridHint = showGridHint,
                        hintArrowId = hintArrowId
                    )

                } else {

                    Text(
                        text = "Level not found",
                        color = Color(0xFF6B7280),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            GameActionBar(
                hintUsesLeft = hintUsesLeft,
                adReady = AdsManager.rewardedReady,
                gridOn = showGridHint,
                onHint = {
                    val activity = context as? android.app.Activity

                    if (hintUsesLeft > 0) {
                        if (showHintNow()) hintUsesLeft -= 1
                    } else if (activity != null) {
                        AdsManager.showRewarded(
                            activity = activity,
                            onReward = { showHintNow() },
                            onUnavailable = {
                                Toast.makeText(
                                    context,
                                    "Ad is loading. Try again in a moment.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                },
                onGrid = { showGridHint = !showGridHint },
                onRestart = { restartLevel() }
            )
        }

        // ---------------- HARD LEVEL ----------------

        AnimatedVisibility(
            visible = showHard,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220))
        ) {
            HardLevelOverlay(level = levelNumber, onDone = { showHard = false })
        }

        // ---------------- WIN ----------------

        AnimatedVisibility(
            visible = winMistakes >= 0,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180))
        ) {
            WinOverlay(
                level = levelNumber,
                mistakes = winMistakes,
                coinsEarned = winCoins,
                coinBalance = coinBalance.intValue,
                bonusAvailable = winCoins > 0 && !bonusClaimed && AdsManager.rewardedReady,
                onBonus = {
                    val activity = context as? android.app.Activity
                    if (activity != null) {
                        AdsManager.showRewarded(
                            activity = activity,
                            onReward = {
                                CoinWallet.addCoins(context, winCoins)
                                coinBalance.intValue = CoinWallet.getCoins(context)
                                bonusClaimed = true
                            },
                            onUnavailable = {
                                Toast.makeText(
                                    context,
                                    "Ad is not available right now.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                },
                onRetry = { restartLevel() },
                onNext = {
                    val nextLevel = levelNumber + 1
                    winMistakes = -1
                    showGameOver = false
                    view.reset()

                    if (levels.exists(nextLevel)) onNext(nextLevel) else onBack()
                },
                onMap = onBack
            )
        }

        // ---------------- GAME OVER ----------------

        AnimatedVisibility(
            visible = showGameOver,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180))
        ) {
            GameOverOverlay(
                level = levelNumber,
                adReady = AdsManager.rewardedReady,
                onWatchAd = {
                    val activity = context as? android.app.Activity
                    if (activity != null) {
                        AdsManager.showRewarded(
                            activity = activity,
                            onReward = {
                                engine.reviveAfterRewarded()
                                showGameOver = false
                                brokenHeart = -1
                                lastMistakes = 0
                            },
                            onUnavailable = {
                                Toast.makeText(
                                    context,
                                    "Ad is not available right now.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                },
                onRestart = { restartLevel() },
                onMap = {
                    progress.clearLevelProgress(levelNumber)
                    showGameOver = false
                    onBack()
                }
            )
        }

        // ---------------- LEVEL PICKER ----------------

        if (showLevelPicker) {
            LevelPickerOverlay(
                progress = progress,
                levels = levels,
                current = levelNumber,
                onDismiss = { showLevelPicker = false },
                onSelect = { selected ->
                    showLevelPicker = false
                    onOpenLevel(selected)
                }
            )
        }
    }
}

private fun Modifier.blockTouches(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent().changes.forEach { it.consume() }
            }
        }
    }

// ============================================================
// HEADER
// ============================================================

@Composable
private fun GameHeader(
    level: Int,
    lives: Int,
    arrived: Int,
    totalArrows: Int,
    brokenHeart: Int,
    zoomProvider: () -> Float,
    onBack: () -> Unit,
    onLevels: () -> Unit,
    onReset: () -> Unit,
    onZoomReset: () -> Unit
) {

    val fraction =
        if (totalArrows > 0) (arrived.toFloat() / totalArrows).coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ScreenBackground)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {

        HeaderButton(onClick = onBack) { BackArrowIcon(tint = Color(0xFF27303D)) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 50.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text(
                text = "LEVEL $level",
                color = Color(0xFF1B2330),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier
                    .background(Color(0xFFEFF2F7), RoundedCornerShape(12.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("➜", color = Color(0xFF4D6FFF), fontSize = 11.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "$arrived / $totalArrows ARRIVED",
                    color = Color(0xFF596474),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(Modifier.height(3.dp))

            Box(
                Modifier
                    .width(120.dp)
                    .height(4.dp)
                    .background(Color(0xFFE6E9EF), RoundedCornerShape(2.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(Color(0xFF4D6FFF), RoundedCornerShape(2.dp))
                )
            }

            AnimatedLives(lives = lives, brokenHeart = brokenHeart)

            val zoom = zoomProvider()

            if (zoom > 1.01f) {
                Text(
                    text = "${String.format("%.1f", zoom)}× ZOOM",
                    color = Color(0xFF6B7280),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HeaderButton(onClick = onZoomReset) { ZoomResetIcon(tint = Color(0xFF27303D)) }
            HeaderButton(onClick = onReset) { RestartIcon(tint = Color(0xFF27303D)) }
            HeaderButton(onClick = onLevels) { MenuBarsIcon(tint = Color(0xFF27303D)) }
        }
    }
}

// ============================================================
// LIVES
// ============================================================

@Composable
private fun AnimatedLives(lives: Int, brokenHeart: Int) {

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        repeat(3) { index ->

            val isBroken = index == brokenHeart
            val scale = remember(index) { Animatable(1f) }

            LaunchedEffect(isBroken) {
                if (isBroken) {
                    scale.snapTo(1f)
                    scale.animateTo(1.30f, tween(170, easing = FastOutSlowInEasing))
                    scale.animateTo(1f, tween(230, easing = FastOutSlowInEasing))
                }
            }

            Text(
                text = when {
                    isBroken -> "💔"
                    index < lives -> "❤️"
                    else -> "🤍"
                },
                fontSize = 21.sp,
                modifier = Modifier.graphicsLayer(scaleX = scale.value, scaleY = scale.value)
            )
        }
    }
}

// ============================================================
// HEADER BUTTON
// ============================================================

@Composable
private fun HeaderButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.91f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "headerButtonScale"
    )

    Box(
        modifier = Modifier
            .size(43.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .shadow(
                elevation = if (isPressed) 1.dp else 5.dp,
                shape = RoundedCornerShape(14.dp),
                clip = false
            )
            .background(Color(0xFFDDE2E9), RoundedCornerShape(14.dp))
            .padding(bottom = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White, Color(0xFFF7F8FA), Color(0xFFECEFF4))
                    ),
                    RoundedCornerShape(14.dp)
                )
                .border(1.dp, Color(0xFFD9DEE6), RoundedCornerShape(14.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
                    .size(width = 20.dp, height = 6.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.82f), Color.Transparent)
                        ),
                        RoundedCornerShape(50)
                    )
            )
            icon()
        }
    }
}

// ============================================================
// VECTOR ICONS
// ============================================================

@Composable
private fun BackArrowIcon(tint: Color, iconSize: Dp = 18.dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.68f, h * 0.10f)
            lineTo(w * 0.26f, h * 0.50f)
            lineTo(w * 0.68f, h * 0.90f)
        }
        drawPath(
            path, tint,
            style = Stroke((w * 0.16f).coerceAtLeast(1.6f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun MenuBarsIcon(tint: Color, iconSize: Dp = 18.dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val barHeight = (h * 0.13f).coerceAtLeast(1.6f)
        val gap = h * 0.32f
        listOf(w, w * 0.78f, w * 0.55f).forEachIndexed { index, barWidth ->
            drawRoundRect(
                color = tint,
                topLeft = Offset(0f, index * gap),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barHeight / 2f, barHeight / 2f)
            )
        }
    }
}

@Composable
private fun GridSquaresIcon(tint: Color, iconSize: Dp = 18.dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = size.width
        val gap = w * 0.14f
        val cell = (w - gap) / 2f
        for (row in 0..1) {
            for (col in 0..1) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                    size = Size(cell, cell),
                    cornerRadius = CornerRadius(cell * 0.22f, cell * 0.22f)
                )
            }
        }
    }
}

@Composable
private fun ZoomResetIcon(tint: Color, iconSize: Dp = 18.dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val sw = (w * 0.11f).coerceAtLeast(1.4f)
        val arm = w * 0.28f
        val inset = w * 0.10f
        val c = StrokeCap.Round

        drawLine(tint, Offset(inset, inset + arm), Offset(inset, inset), sw, c)
        drawLine(tint, Offset(inset, inset), Offset(inset + arm, inset), sw, c)
        drawLine(tint, Offset(w - inset - arm, inset), Offset(w - inset, inset), sw, c)
        drawLine(tint, Offset(w - inset, inset), Offset(w - inset, inset + arm), sw, c)
        drawLine(tint, Offset(inset, h - inset - arm), Offset(inset, h - inset), sw, c)
        drawLine(tint, Offset(inset, h - inset), Offset(inset + arm, h - inset), sw, c)
        drawLine(tint, Offset(w - inset - arm, h - inset), Offset(w - inset, h - inset), sw, c)
        drawLine(tint, Offset(w - inset, h - inset), Offset(w - inset, h - inset - arm), sw, c)
    }
}

@Composable
private fun CloseXIcon(tint: Color, iconSize: Dp = 18.dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val sw = (w * 0.14f).coerceAtLeast(1.6f)
        val inset = w * 0.18f
        drawLine(tint, Offset(inset, inset), Offset(w - inset, h - inset), sw, StrokeCap.Round)
        drawLine(tint, Offset(w - inset, inset), Offset(inset, h - inset), sw, StrokeCap.Round)
    }
}

@Composable
private fun RestartIcon(tint: Color, iconSize: Dp = 18.dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val sw = (w * 0.16f).coerceAtLeast(1.6f)
        val inset = sw / 2f + w * 0.05f

        drawArc(
            color = tint,
            startAngle = -40f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(w - inset * 2f, h - inset * 2f),
            style = Stroke(width = sw, cap = StrokeCap.Round)
        )

        val headSize = w * 0.30f
        val tipX = w * 0.90f
        val tipY = h * 0.28f

        val head = Path().apply {
            moveTo(tipX, tipY)
            lineTo(tipX - headSize, tipY - headSize * 0.20f)
            lineTo(tipX - headSize * 0.35f, tipY + headSize * 0.75f)
            close()
        }
        drawPath(head, tint)
    }
}

@Composable
private fun HintBulbIcon(tint: Color, iconSize: Dp = 20.dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.12f).coerceAtLeast(1.5f)
        val bulb = Path().apply {
            moveTo(w * 0.50f, h * 0.08f)
            cubicTo(w * 0.20f, h * 0.08f, w * 0.08f, h * 0.34f, w * 0.18f, h * 0.55f)
            cubicTo(w * 0.23f, h * 0.66f, w * 0.36f, h * 0.68f, w * 0.38f, h * 0.82f)
            lineTo(w * 0.62f, h * 0.82f)
            cubicTo(w * 0.64f, h * 0.68f, w * 0.77f, h * 0.66f, w * 0.82f, h * 0.55f)
            cubicTo(w * 0.92f, h * 0.34f, w * 0.80f, h * 0.08f, w * 0.50f, h * 0.08f)
        }
        drawPath(bulb, tint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(tint, Offset(w * 0.38f, h * 0.90f), Offset(w * 0.62f, h * 0.90f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.42f, h * 0.98f), Offset(w * 0.58f, h * 0.98f), stroke, StrokeCap.Round)
        drawLine(tint.copy(alpha = 0.7f), Offset(w * 0.50f, h * 0.30f), Offset(w * 0.50f, h * 0.57f), stroke, StrokeCap.Round)
    }
}

// ============================================================
// BOARD GEOMETRY
// ============================================================

private data class BoardBounds(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int
)

/** Bounding box (in cells) of every arrow, i.e. the part of the grid that matters. */
private fun computeBounds(engine: ArrowGameEngine): BoardBounds {

    val grid = engine.gridSize.coerceAtLeast(1)

    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE

    engine.arrows.forEach { arrow ->
        for (i in arrow.pathX.indices) {
            val x = arrow.pathX[i]
            val y = arrow.pathY[i]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }

    if (minX == Int.MAX_VALUE) return BoardBounds(0, 0, grid - 1, grid - 1)

    return BoardBounds(
        minX.coerceAtLeast(0),
        minY.coerceAtLeast(0),
        maxX.coerceAtMost(grid - 1),
        maxY.coerceAtMost(grid - 1)
    )
}

private data class RenderMetrics(
    val cellSize: Float,
    val worldCenterX: Float,
    val worldCenterY: Float,
    val screenCenterX: Float,
    val screenCenterY: Float,
    val panX: Float,
    val panY: Float,
    val zoom: Float,
    // Visible board rectangle in world pixels (used by the direction guides).
    val edgeLeft: Float,
    val edgeTop: Float,
    val edgeRight: Float,
    val edgeBottom: Float
)

private fun calculateMetrics(
    viewWidth: Float,
    viewHeight: Float,
    bounds: BoardBounds,
    zoom: Float,
    pan: Offset,
    maxCell: Float
): RenderMetrics {

    val w = viewWidth.coerceAtLeast(1f)
    val h = viewHeight.coerceAtLeast(1f)

    val cellsW = (bounds.maxX - bounds.minX + 1) + BOARD_PADDING_CELLS * 2f
    val cellsH = (bounds.maxY - bounds.minY + 1) + BOARD_PADDING_CELLS * 2f

    val cell = min(min(w * FIT_RATIO / cellsW, h * FIT_RATIO / cellsH), maxCell)

    val contentW = cellsW * cell
    val contentH = cellsH * cell

    val safeZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    val maxPanX = max(0f, (contentW * safeZoom - w) / 2f + cell * safeZoom)
    val maxPanY = max(0f, (contentH * safeZoom - h) / 2f + cell * safeZoom)

    return RenderMetrics(
        cellSize = cell,
        worldCenterX = (bounds.minX + bounds.maxX + 1) / 2f * cell,
        worldCenterY = (bounds.minY + bounds.maxY + 1) / 2f * cell,
        screenCenterX = w / 2f,
        screenCenterY = h / 2f,
        panX = pan.x.coerceIn(-maxPanX, maxPanX),
        panY = pan.y.coerceIn(-maxPanY, maxPanY),
        zoom = safeZoom,
        edgeLeft = (bounds.minX - BOARD_PADDING_CELLS) * cell,
        edgeTop = (bounds.minY - BOARD_PADDING_CELLS) * cell,
        edgeRight = (bounds.maxX + 1 + BOARD_PADDING_CELLS) * cell,
        edgeBottom = (bounds.maxY + 1 + BOARD_PADDING_CELLS) * cell
    )
}

private fun DrawScope.boardTransform(m: RenderMetrics, block: DrawScope.() -> Unit) {
    withTransform({
        translate(left = m.screenCenterX + m.panX, top = m.screenCenterY + m.panY)
        scale(scaleX = m.zoom, scaleY = m.zoom, pivot = Offset.Zero)
        translate(left = -m.worldCenterX, top = -m.worldCenterY)
    }) { block() }
}

private fun screenToWorld(screen: Offset, m: RenderMetrics): Offset {
    val inv = 1f / m.zoom.coerceAtLeast(0.0001f)
    return Offset(
        m.worldCenterX + (screen.x - m.screenCenterX - m.panX) * inv,
        m.worldCenterY + (screen.y - m.screenCenterY - m.panY) * inv
    )
}

private fun worldToScreen(world: Offset, m: RenderMetrics): Offset =
    Offset(
        m.screenCenterX + m.panX + (world.x - m.worldCenterX) * m.zoom,
        m.screenCenterY + m.panY + (world.y - m.worldCenterY) * m.zoom
    )

// ============================================================
// GAME BOARD
// ============================================================

private class ArrowScratch {
    val body = Path()
    val head = Path()
}

@Composable
private fun GameBoard(
    engine: ArrowGameEngine,
    arrowColor: Color,
    view: BoardViewState,
    showGridHint: Boolean,
    hintArrowId: Int?
) {

    val density = LocalDensity.current.density
    val maxCellPx = MAX_CELL_DP * density
    val touchRadiusPx = TOUCH_RADIUS_DP * density

    val bounds = remember(engine.level, engine.gridSize) { computeBounds(engine) }
    val currentBounds by rememberUpdatedState(bounds)

    // Static dot grid (in cell units) - built once per level.
    val dotPoints = remember(bounds, engine.gridSize) {
        val grid = engine.gridSize
        val x0 = (bounds.minX - 1).coerceAtLeast(0)
        val x1 = (bounds.maxX + 1).coerceAtMost(grid - 1)
        val y0 = (bounds.minY - 1).coerceAtLeast(0)
        val y1 = (bounds.maxY + 1).coerceAtMost(grid - 1)
        val list = ArrayList<Offset>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (x in x0..x1) for (y in y0..y1) list.add(Offset(x + 0.5f, y + 0.5f))
        list
    }

    // Long-press direction guide: stays until tap / arrow moves.
    var previewArrowId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(engine) {
        snapshotFlow {
            val id = previewArrowId
            id != null && engine.arrows.firstOrNull { it.id == id }?.status != GameArrow.Status.IDLE
        }.collect { stale -> if (stale) previewArrowId = null }
    }

    // Touch ripple: shown on EVERY tap (also on empty space).
    val scope = rememberCoroutineScope()
    val ripple = remember { Animatable(1f) }
    var ripplePos by remember { mutableStateOf(Offset.Unspecified) }

    fun fireRipple(world: Offset) {
        ripplePos = world
        scope.launch {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        }
    }

    // Only read while a hint / guide is visible.
    val pulseTransition = rememberInfiniteTransition(label = "hintPulse")
    val hintPulse by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(700, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "hintPulseValue"
    )

    val scratch = remember { ArrowScratch() }

    Box(Modifier.fillMaxSize()) {

        // ------------------------------------------------
        // LAYER 1: static dots (redraws only on zoom / pan)
        // ------------------------------------------------

        Canvas(Modifier.fillMaxSize()) {

            val m = calculateMetrics(
                size.width, size.height, bounds, view.zoom, view.pan, maxCellPx
            )

            clipRect {
                boardTransform(m) {
                    val cell = m.cellSize
                    val diameter = max(2.2f, cell * 0.07f)

                    withTransform({
                        scale(scaleX = cell, scaleY = cell, pivot = Offset.Zero)
                    }) {
                        drawPoints(
                            points = dotPoints,
                            pointMode = PointMode.Points,
                            color = Color(0xFFDCD6CD),
                            strokeWidth = diameter / cell,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }

        // ------------------------------------------------
        // LAYER 2: arrows + input
        // ------------------------------------------------

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(engine) {

                    awaitEachGesture {

                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTime = down.uptimeMillis
                        val slop = viewConfiguration.touchSlop

                        var moved = false
                        var multi = false
                        var longPressed = false

                        var lastPos = down.position
                        var localZoom = view.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
                        var localPan = view.pan
                        var prevCentroid = Offset.Zero
                        var prevSpan = 0f

                        while (true) {

                            val canLongPress = !moved && !multi && !longPressed

                            // Real timeout: the long press fires even if the
                            // finger is perfectly still.
                            val event =
                                if (canLongPress) {
                                    val remaining =
                                        LONG_PRESS_MS - (SystemClock.uptimeMillis() - downTime)
                                    if (remaining <= 0L) null
                                    else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                } else {
                                    awaitPointerEvent()
                                }

                            if (event == null) {

                                longPressed = true

                                val m = calculateMetrics(
                                    size.width.toFloat(), size.height.toFloat(),
                                    currentBounds, localZoom, localPan, maxCellPx
                                )

                                val target = findBestTapPoint(
                                    engine = engine,
                                    world = screenToWorld(lastPos, m),
                                    cell = m.cellSize,
                                    zoom = localZoom,
                                    touchRadiusPx = touchRadiusPx,
                                    radiusMultiplier = LONG_PRESS_RADIUS_MULTIPLIER
                                )

                                target.arrowId?.let { previewArrowId = it }

                                continue
                            }

                            val active = event.changes.filter { it.pressed }

                            if (active.isEmpty()) break

                            // ---------------- PINCH ----------------

                            if (active.size >= 2) {

                                val centroid = pointerCentroid(active)
                                val span = pointerSpan(active)

                                if (!multi) {
                                    multi = true
                                    moved = true
                                    prevCentroid = centroid
                                    prevSpan = span
                                    active.forEach { it.consume() }
                                    continue
                                }

                                val factor =
                                    if (prevSpan > 0.1f && span > 0.1f)
                                        (span / prevSpan).coerceIn(0.90f, 1.10f)
                                    else 1f

                                val oldZoom = localZoom
                                val newZoom = (oldZoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                val actual = if (oldZoom > 0f) newZoom / oldZoom else 1f

                                val viewCenter = Offset(size.width / 2f, size.height / 2f)
                                val focal = centroid - viewCenter

                                val requestedPan =
                                    localPan * actual +
                                            focal * (1f - actual) +
                                            (centroid - prevCentroid)

                                val m = calculateMetrics(
                                    size.width.toFloat(), size.height.toFloat(),
                                    currentBounds, newZoom, requestedPan, maxCellPx
                                )

                                localZoom = m.zoom
                                localPan = Offset(m.panX, m.panY)

                                view.zoom = localZoom
                                view.pan = localPan

                                prevCentroid = centroid
                                prevSpan = span

                                active.forEach { it.consume() }
                                continue
                            }

                            // ---------------- SINGLE FINGER ----------------

                            val change = active.first()
                            val pos = change.position

                            if (multi) {
                                multi = false
                                lastPos = pos
                                change.consume()
                                continue
                            }

                            if (!moved && (pos - down.position).getDistance() > slop) {
                                moved = true
                            }

                            if (moved && localZoom > MIN_ZOOM + 0.001f) {

                                val m = calculateMetrics(
                                    size.width.toFloat(), size.height.toFloat(),
                                    currentBounds, localZoom, localPan + (pos - lastPos), maxCellPx
                                )

                                localPan = Offset(m.panX, m.panY)
                                view.pan = localPan
                            }

                            lastPos = pos
                            change.consume()
                        }

                        // ---------------- TAP ----------------

                        if (!moved && !multi && !longPressed) {

                            val m = calculateMetrics(
                                size.width.toFloat(), size.height.toFloat(),
                                currentBounds, localZoom, localPan, maxCellPx
                            )

                            val world = screenToWorld(down.position, m)

                            fireRipple(world)

                            val target = findBestTapPoint(
                                engine = engine,
                                world = world,
                                cell = m.cellSize,
                                zoom = localZoom,
                                touchRadiusPx = touchRadiusPx
                            )

                            target.arrowId?.let { id ->
                                if (previewArrowId == id) previewArrowId = null

                                engine.tapArrow(
                                    arrowId = id,
                                    worldX = target.point.x,
                                    worldY = target.point.y,
                                    cellSize = m.cellSize
                                )
                            }
                        }
                    }
                }
        ) {

            // Redraw trigger for particles / dot glow (only ticks while animating).
            @Suppress("UNUSED_VARIABLE")
            val tick = engine.frameTick

            val m = calculateMetrics(
                size.width, size.height, bounds, view.zoom, view.pan, maxCellPx
            )

            val cell = m.cellSize

            clipRect {

                boardTransform(m) {

                    // ---------- dot glow ----------

                    val finalGlow = engine.finalDotGlow

                    if (finalGlow > 0.001f) {
                        withTransform({
                            scale(scaleX = cell, scaleY = cell, pivot = Offset.Zero)
                        }) {
                            drawPoints(
                                points = dotPoints,
                                pointMode = PointMode.Points,
                                color = arrowColor.copy(alpha = (finalGlow * 0.16f).coerceIn(0f, 0.2f)),
                                strokeWidth = (0.26f + finalGlow * 0.14f),
                                cap = StrokeCap.Round
                            )
                        }
                    }

                    if (engine.dotGlows.isNotEmpty()) {
                        for ((key, value) in engine.dotGlows) {
                            val sep = key.indexOf(':')
                            if (sep <= 0) continue
                            val gx = key.substring(0, sep).toIntOrNull() ?: continue
                            val gy = key.substring(sep + 1).toIntOrNull() ?: continue
                            val glow = value.coerceIn(0f, 1f)

                            drawCircle(
                                color = arrowColor.copy(alpha = (glow * 0.14f).coerceIn(0f, 0.18f)),
                                radius = cell * (0.13f + glow * 0.20f),
                                center = Offset((gx + 0.5f) * cell, (gy + 0.5f) * cell)
                            )
                        }
                    }

                    // ---------- arrows ----------

                    engine.arrows.forEach { arrow ->
                        drawArrow(
                            arrow = arrow,
                            cell = cell,
                            baseColor = arrowColor,
                            scratch = scratch,
                            highlighted = arrow.id == hintArrowId,
                            pulse = hintPulse
                        )
                    }

                    // ---------- guides ----------

                    if (showGridHint) {
                        engine.arrows.forEach { arrow ->
                            if (arrow.status == GameArrow.Status.IDLE) {
                                drawDirectionGuide(arrow, cell, m, arrowColor, false, 0f)
                            }
                        }
                    }

                    val previewId = previewArrowId

                    if (!showGridHint && previewId != null) {
                        engine.arrows.firstOrNull { it.id == previewId }?.let { arrow ->
                            if (arrow.status == GameArrow.Status.IDLE) {
                                drawDirectionGuide(arrow, cell, m, HintBlue, true, hintPulse)
                            }
                        }
                    }

                    if (hintArrowId != null) {
                        engine.arrows.firstOrNull { it.id == hintArrowId }?.let { arrow ->
                            if (arrow.status == GameArrow.Status.IDLE) {
                                drawDirectionGuide(arrow, cell, m, HintBlue, true, hintPulse)
                            }
                        }
                    }

                    // ---------- particles ----------

                    engine.particles.forEach { p ->
                        drawCircle(
                            color = Color(p.color.toInt()).copy(alpha = p.life.coerceIn(0f, 1f)),
                            radius = max(1.2f, p.radius),
                            center = Offset(p.x * cell + cell / 2f, p.y * cell + cell / 2f)
                        )
                    }
                }

                // ---------- touch ripple (screen space, always visible) ----------

                val r = ripple.value

                if (r < 1f && ripplePos.isSpecified) {

                    val center = worldToScreen(ripplePos, m)
                    val fade = 1f - r

                    drawCircle(
                        color = arrowColor.copy(alpha = 0.14f * fade),
                        radius = (10f + 40f * r) * density,
                        center = center
                    )

                    drawCircle(
                        color = arrowColor.copy(alpha = 0.45f * fade),
                        radius = (10f + 40f * r) * density,
                        center = center,
                        style = Stroke(width = 2f * density)
                    )
                }
            }
        }
    }
}

private val Offset.isSpecified: Boolean
    get() = this != Offset.Unspecified

// ============================================================
// POINTER MATH
// ============================================================

private fun pointerCentroid(pointers: List<PointerInputChange>): Offset {
    if (pointers.isEmpty()) return Offset.Zero
    var x = 0f
    var y = 0f
    pointers.forEach { x += it.position.x; y += it.position.y }
    return Offset(x / pointers.size, y / pointers.size)
}

private fun pointerSpan(pointers: List<PointerInputChange>): Float {
    if (pointers.size < 2) return 0f
    var total = 0f
    var pairs = 0
    for (i in 0 until pointers.lastIndex) {
        for (j in i + 1 until pointers.size) {
            total += (pointers[i].position - pointers[j].position).getDistance()
            pairs++
        }
    }
    return if (pairs > 0) total / pairs else 0f
}

// ============================================================
// HIT TESTING
// ============================================================

private data class TapTarget(
    val point: Offset,
    val arrowId: Int?
)

private fun findBestTapPoint(
    engine: ArrowGameEngine,
    world: Offset,
    cell: Float,
    zoom: Float,
    touchRadiusPx: Float,
    radiusMultiplier: Float = 1f
): TapTarget {

    // The finger radius is fixed in SCREEN pixels, so dense levels with tiny
    // cells are still easy to tap. The nearest arrow segment wins.
    var bestDistance =
        max(cell * TOUCH_RADIUS, touchRadiusPx / zoom.coerceAtLeast(1f)) * radiusMultiplier

    var bestPoint = world
    var bestArrowId: Int? = null

    val half = cell / 2f

    for (index in engine.arrows.indices.reversed()) {

        val arrow = engine.arrows[index]

        if (arrow.status != GameArrow.Status.IDLE) continue

        val points = arrow.renderPath

        if (points.isEmpty()) continue

        if (points.size == 1) {

            val p = Offset(points[0].x * cell + half, points[0].y * cell + half)
            val d = distance(world, p)

            if (d < bestDistance) {
                bestDistance = d
                bestPoint = p
                bestArrowId = arrow.id
            }

            continue
        }

        for (i in 0 until points.lastIndex) {

            val a = Offset(points[i].x * cell + half, points[i].y * cell + half)
            val b = Offset(points[i + 1].x * cell + half, points[i + 1].y * cell + half)

            val closest = closestPointOnSegment(world, a, b)
            val d = distance(world, closest)

            if (d < bestDistance) {
                bestDistance = d
                bestPoint = closest
                bestArrowId = arrow.id
            }
        }
    }

    return TapTarget(bestPoint, bestArrowId)
}

private fun closestPointOnSegment(point: Offset, start: Offset, end: Offset): Offset {

    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy

    if (lengthSquared <= 0.0001f) return start

    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared)
        .coerceIn(0f, 1f)

    return Offset(start.x + dx * t, start.y + dy * t)
}

private fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

// ============================================================
// DRAW ARROW
// Thin, clean line with a solid rounded arrowhead (reference style).
// Thickness adapts to cell size so neighbouring arrows never merge.
// ============================================================

private fun DrawScope.drawArrow(
    arrow: GameArrow,
    cell: Float,
    baseColor: Color,
    scratch: ArrowScratch,
    highlighted: Boolean,
    pulse: Float
) {

    if (arrow.status == GameArrow.Status.REMOVED) return

    val points = arrow.renderPath

    if (points.isEmpty()) return

    val color = when {
        highlighted -> HintBlue
        arrow.redGlow -> CollisionRed
        arrow.status == GameArrow.Status.WAITING_WHITE -> WaitingGray
        else -> baseColor
    }

    val ratio = when {
        cell >= 60f -> 0.105f
        cell >= 40f -> 0.12f
        cell >= 24f -> 0.14f
        cell >= 14f -> 0.16f
        else -> 0.18f
    }

    val stroke = (cell * ratio).coerceIn(1.4f, 14f)

    val ox = arrow.offsetX * cell + cell / 2f
    val oy = arrow.offsetY * cell + cell / 2f

    val body = scratch.body
    body.reset()

    points.forEachIndexed { i, p ->
        val x = p.x * cell + ox
        val y = p.y * cell + oy
        if (i == 0) body.moveTo(x, y) else body.lineTo(x, y)
    }

    // ---- soft halo (hint / collision) ----

    if (highlighted || arrow.blockedPulse > 0f) {

        val haloAlpha =
            if (highlighted) 0.14f + 0.12f * pulse
            else 0.08f + 0.22f * arrow.blockedPulse.coerceIn(0f, 1f)

        val haloWidth = stroke * (if (highlighted) 3.2f + pulse * 0.6f else 2.4f)

        if (points.size >= 2) {
            drawPath(
                body,
                color.copy(alpha = haloAlpha),
                style = Stroke(haloWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        } else {
            drawCircle(
                color.copy(alpha = haloAlpha),
                haloWidth / 2f,
                Offset(points[0].x * cell + ox, points[0].y * cell + oy)
            )
        }
    }

    // ---- body ----

    if (points.size >= 2) {
        drawPath(
            body,
            color,
            style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    } else {
        drawCircle(
            color,
            stroke / 2f,
            Offset(points[0].x * cell + ox, points[0].y * cell + oy)
        )
    }

    // ---- head ----

    val head = points.last()
    val previous = points.getOrNull(points.lastIndex - 1) ?: head

    var dx = head.x - previous.x
    var dy = head.y - previous.y

    if (abs(dx) < 0.001f && abs(dy) < 0.001f) {
        when (arrow.direction) {
            Direction.UP -> { dx = 0f; dy = -1f }
            Direction.DOWN -> { dx = 0f; dy = 1f }
            Direction.LEFT -> { dx = -1f; dy = 0f }
            Direction.RIGHT -> { dx = 1f; dy = 0f }
        }
    }

    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
    dx /= len
    dy /= len

    val px = -dy
    val py = dx

    val hx = head.x * cell + ox
    val hy = head.y * cell + oy

    val headLength = stroke * 2.9f
    val halfWidth = stroke * 1.5f
    val back = stroke * 0.25f

    val headPath = scratch.head
    headPath.reset()
    headPath.moveTo(hx + dx * headLength, hy + dy * headLength)
    headPath.lineTo(hx - dx * back + px * halfWidth, hy - dy * back + py * halfWidth)
    headPath.lineTo(hx - dx * back - px * halfWidth, hy - dy * back - py * halfWidth)
    headPath.close()

    drawPath(headPath, color)

    // A thin same-colour stroke rounds the triangle corners.
    drawPath(
        headPath,
        color,
        style = Stroke(stroke * 0.35f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

// ============================================================
// DIRECTION GUIDE (long press / hint / grid guide)
// ============================================================

private fun DrawScope.drawDirectionGuide(
    arrow: GameArrow,
    cell: Float,
    m: RenderMetrics,
    color: Color,
    animated: Boolean,
    pulse: Float
) {

    if (arrow.status == GameArrow.Status.REMOVED) return

    val points = arrow.renderPath

    if (points.isEmpty()) return

    val head = points.last()
    val previous = points.getOrNull(points.lastIndex - 1) ?: head

    var dx = head.x - previous.x
    var dy = head.y - previous.y

    if (abs(dx) < 0.001f && abs(dy) < 0.001f) {
        when (arrow.direction) {
            Direction.UP -> { dx = 0f; dy = -1f }
            Direction.DOWN -> { dx = 0f; dy = 1f }
            Direction.LEFT -> { dx = -1f; dy = 0f }
            Direction.RIGHT -> { dx = 1f; dy = 0f }
        }
    }

    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
    dx /= len
    dy /= len

    val start = Offset(
        head.x * cell + cell / 2f + arrow.offsetX * cell + dx * cell * 0.55f,
        head.y * cell + cell / 2f + arrow.offsetY * cell + dy * cell * 0.55f
    )

    val tx = when {
        dx > 0f -> (m.edgeRight - start.x) / dx
        dx < 0f -> (m.edgeLeft - start.x) / dx
        else -> Float.MAX_VALUE
    }

    val ty = when {
        dy > 0f -> (m.edgeBottom - start.y) / dy
        dy < 0f -> (m.edgeTop - start.y) / dy
        else -> Float.MAX_VALUE
    }

    val travel = min(tx, ty).coerceAtLeast(cell)

    val alpha = if (animated) 0.70f + 0.25f * pulse else 0.70f
    val width = max(1.9f, cell * if (animated) 0.058f else 0.045f)

    val end = Offset(start.x + dx * travel, start.y + dy * travel)

    drawLine(color.copy(alpha = alpha), start, end, width, StrokeCap.Round)

    val px = -dy
    val py = dx
    val headSize = max(4.5f, cell * 0.13f)

    val backPoint = Offset(end.x - dx * headSize, end.y - dy * headSize)

    val tip = Path().apply {
        moveTo(backPoint.x + px * headSize * 0.6f, backPoint.y + py * headSize * 0.6f)
        lineTo(end.x, end.y)
        lineTo(backPoint.x - px * headSize * 0.6f, backPoint.y - py * headSize * 0.6f)
    }

    drawPath(
        tip,
        color.copy(alpha = min(1f, alpha + 0.1f)),
        style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

// ============================================================
// ACTION BAR
// Ads are always OPTIONAL and player-initiated:
//  - Hint: 2 free per level, then a rewarded ad gives one more.
//  - Win screen: optional "watch ad for bonus coins".
//  - Game over: optional "watch ad to continue".
// Nothing interrupts play.
// ============================================================

@Composable
private fun GameActionBar(
    hintUsesLeft: Int,
    adReady: Boolean,
    gridOn: Boolean,
    onHint: () -> Unit,
    onGrid: () -> Unit,
    onRestart: () -> Unit
) {

    val hintTitle = when (hintUsesLeft) {
        2 -> "2 FREE HINTS"
        1 -> "1 FREE HINT"
        else -> if (adReady) "AD HINT" else "AD LOADING"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom
    ) {

        ModernActionItem(
            title = hintTitle,
            titleColor = when {
                hintUsesLeft > 0 -> Color(0xFF2D9B55)
                adReady -> Color(0xFF4F72FF)
                else -> Color(0xFF9AA3B2)
            },
            badge = if (hintUsesLeft > 0) hintUsesLeft.toString() else "AD",
            badgeMuted = hintUsesLeft <= 0 && !adReady,
            onClick = onHint
        ) {
            HintBulbIcon(
                tint = if (hintUsesLeft > 0) Color(0xFF40557C) else Color(0xFF4F72FF)
            )
        }

        Spacer(Modifier.width(22.dp))

        ModernActionItem(
            title = "GRID GUIDE",
            titleColor = if (gridOn) Color(0xFF4F72FF) else Color(0xFF687386),
            onClick = onGrid
        ) {
            GridSquaresIcon(
                tint = if (gridOn) Color(0xFF4F72FF) else Color(0xFF40557C),
                iconSize = 21.dp
            )
        }

        Spacer(Modifier.width(22.dp))

        ModernActionItem(
            title = "RESTART",
            titleColor = Color(0xFF687386),
            onClick = onRestart
        ) {
            RestartIcon(tint = Color(0xFF40557C), iconSize = 21.dp)
        }
    }
}

@Composable
private fun ModernActionItem(
    title: String,
    titleColor: Color,
    onClick: () -> Unit,
    badge: String? = null,
    badgeMuted: Boolean = false,
    icon: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = title,
            color = titleColor,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.45.sp,
            maxLines = 1
        )
        ActionCircleButton(onClick = onClick, badge = badge, badgeMuted = badgeMuted, icon = icon)
    }
}

@Composable
private fun ActionCircleButton(
    onClick: () -> Unit,
    badge: String? = null,
    badgeMuted: Boolean = false,
    icon: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.91f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "actionButtonScale"
    )

    val isAd = badge == "AD"
    val badgeShape = if (isAd) RoundedCornerShape(9.dp) else CircleShape

    Box(
        modifier = Modifier
            .size(64.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .shadow(
                elevation = if (isPressed) 2.dp else 8.dp,
                shape = CircleShape,
                clip = false
            )
            .background(Color(0xFFD8DEE7), CircleShape)
            .padding(bottom = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(61.dp)
                .shadow(
                    elevation = if (isPressed) 1.dp else 3.dp,
                    shape = CircleShape,
                    clip = false
                )
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White, Color(0xFFF7F8FB), Color(0xFFE9EDF3))
                    ),
                    CircleShape
                )
                .border(1.dp, Color(0xFFD7DDE6), CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 3.dp)
                    .size(width = 30.dp, height = 8.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.78f), Color.White.copy(alpha = 0f))
                        ),
                        RoundedCornerShape(50)
                    )
            )
            icon()
        }

        if (badge != null) {

            val badgeColor = when {
                isAd && badgeMuted -> Color(0xFFB4BBC7)
                isAd -> Color(0xFF4F72FF)
                else -> Color(0xFF35B967)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 7.dp, y = (-7).dp)
                    .zIndex(5f)
                    .shadow(3.dp, badgeShape, clip = false)
                    .background(badgeColor, badgeShape)
                    .border(1.dp, Color.White.copy(alpha = 0.88f), badgeShape)
                    .then(
                        if (isAd) Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        else Modifier.size(21.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badge,
                    color = Color.White,
                    fontSize = if (isAd) 8.sp else 10.sp,
                    lineHeight = if (isAd) 9.sp else 11.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

// ============================================================
// HARD LEVEL OVERLAY
// ============================================================

@Composable
private fun HardLevelOverlay(level: Int, onDone: () -> Unit) {

    val transition = rememberInfiniteTransition(label = "hard")

    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "hardPulse"
    )

    val ringRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(2600, easing = LinearEasing), RepeatMode.Restart
        ),
        label = "hardRingRotation"
    )

    val ringAlpha by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.50f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "hardRingAlpha"
    )

    val entrance = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        entrance.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        )
        delay(1000L)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(listOf(Color(0xE6241318), Color(0xF0140B0E)))
            )
            .blockTouches(),
        contentAlignment = Alignment.Center
    ) {

        Canvas(
            Modifier
                .size(190.dp)
                .graphicsLayer(rotationZ = ringRotation)
        ) {
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0xFFFF7A45).copy(alpha = ringAlpha),
                        Color.Transparent,
                        Color(0xFFFF7A45).copy(alpha = ringAlpha)
                    )
                ),
                startAngle = 0f,
                sweepAngle = 300f,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .graphicsLayer(
                    scaleX = entrance.value,
                    scaleY = entrance.value,
                    alpha = entrance.value
                )
                .padding(24.dp)
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF2B1B20), Color(0xFF1A1013))),
                    RoundedCornerShape(26.dp)
                )
                .border(1.dp, Color(0xFFFF7A45).copy(alpha = 0.35f), RoundedCornerShape(26.dp))
                .padding(30.dp)
        ) {

            Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                Text("⚡", fontSize = (48f * pulse).sp)
            }

            Text("HARD LEVEL", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)

            Spacer(Modifier.height(4.dp))

            Text(
                "LEVEL $level",
                color = Color(0xFFFF9D6C),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(10.dp))

            Text(
                "STAY SHARP — ONE WRONG MOVE COSTS A LIFE",
                color = Color(0xFFC9B4AE),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}

// ============================================================
// WIN OVERLAY
// ============================================================

@Composable
private fun WinOverlay(
    level: Int,
    mistakes: Int,
    coinsEarned: Int,
    coinBalance: Int,
    bonusAvailable: Boolean,
    onBonus: () -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
    onMap: () -> Unit
) {

    val stars = (3 - mistakes).coerceIn(1, 3)

    val entrance = remember { Animatable(0f) }
    val trophyScale = remember { Animatable(0.55f) }
    val ribbonOffset = remember { Animatable(-60f) }

    val transition = rememberInfiniteTransition(label = "premiumWin")

    val sparkleProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1250, easing = LinearEasing), RepeatMode.Restart),
        label = "sparkleProgress"
    )

    val softPulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "softPulse"
    )

    LaunchedEffect(Unit) {
        launch {
            entrance.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )
        }
        launch { ribbonOffset.animateTo(0f, tween(620, easing = FastOutSlowInEasing)) }
        launch {
            delay(150L)
            trophyScale.animateTo(
                1.14f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
            )
            trophyScale.animateTo(1f, tween(230, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(
                        Color(0xFFFFE9A5).copy(alpha = 0.58f),
                        Color(0xFFF7F2E9).copy(alpha = 0.97f),
                        Color(0xFFECE6DC).copy(alpha = 0.99f)
                    )
                )
            )
            .blockTouches()
    ) {

        WinFallingParticles(progress = sparkleProgress)
        WinSparkles(progress = sparkleProgress)

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.88f)
                .graphicsLayer(
                    scaleX = entrance.value,
                    scaleY = entrance.value,
                    alpha = entrance.value
                )
                .shadow(
                    elevation = 20.dp,
                    shape = RoundedCornerShape(28.dp),
                    clip = false,
                    ambientColor = Color(0xFFD99B24).copy(alpha = 0.28f),
                    spotColor = Color(0xFFD99B24).copy(alpha = 0.30f)
                )
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFFFFEFA), Color(0xFFFFF5D8), Color(0xFFF4DEAD))
                    ),
                    RoundedCornerShape(28.dp)
                )
                .border(1.dp, Color(0xFFE1B454).copy(alpha = 0.72f), RoundedCornerShape(28.dp))
                .padding(horizontal = 22.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Box(
                modifier = Modifier
                    .offset(y = ribbonOffset.value.dp)
                    .fillMaxWidth(0.92f)
                    .height(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val ribbon = Path().apply {
                        moveTo(w * 0.08f, h * 0.16f)
                        cubicTo(w * 0.20f, h * 0.02f, w * 0.30f, h * 0.34f, w * 0.50f, h * 0.18f)
                        cubicTo(w * 0.70f, h * 0.34f, w * 0.80f, h * 0.02f, w * 0.92f, h * 0.16f)
                        lineTo(w * 0.86f, h * 0.82f)
                        cubicTo(w * 0.70f, h * 0.98f, w * 0.60f, h * 0.66f, w * 0.50f, h * 0.80f)
                        cubicTo(w * 0.40f, h * 0.66f, w * 0.30f, h * 0.98f, w * 0.14f, h * 0.82f)
                        close()
                    }
                    drawPath(
                        ribbon,
                        brush = Brush.verticalGradient(listOf(Color(0xFFFF5C67), Color(0xFFD92C3B)))
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.30f),
                        Offset(w * 0.18f, h * 0.25f),
                        Offset(w * 0.82f, h * 0.25f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                Text(
                    "YOU WON!",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp
                )
            }

            Spacer(Modifier.height(3.dp))

            Text(
                "LEVEL $level COMPLETE",
                color = Color(0xFF6E4B22),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )

            Spacer(Modifier.height(6.dp))

            PremiumAnimatedStars(stars = stars)

            Spacer(Modifier.height(5.dp))

            Box(
                modifier = Modifier
                    .size(110.dp)
                    .graphicsLayer(
                        scaleX = trophyScale.value * softPulse,
                        scaleY = trophyScale.value * softPulse
                    ),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color(0xFFFFF0A7), Color(0xFFFFD24C), Color(0xFFFFB000))
                        ),
                        radius = size.minDimension * 0.46f
                    )
                    drawCircle(
                        Color.White.copy(alpha = 0.36f),
                        radius = size.minDimension * 0.34f,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
                Text("🏆", fontSize = 50.sp)
            }

            Spacer(Modifier.height(6.dp))

            // ---- coin reward ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFE9A9), RoundedCornerShape(15.dp))
                    .border(1.dp, Color(0xFFE8B94E).copy(alpha = 0.75f), RoundedCornerShape(15.dp))
                    .padding(horizontal = 13.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(35.dp)
                        .shadow(5.dp, CircleShape, clip = false)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFFFFF2A9), Color(0xFFFFC928), Color(0xFFE99A00))
                            ),
                            CircleShape
                        )
                        .border(1.dp, Color(0xFFFFF5BE), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("🪙", fontSize = 20.sp) }

                Spacer(Modifier.width(9.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        if (coinsEarned > 0) "+$coinsEarned COINS" else "NO NEW COINS",
                        color = Color(0xFF7A5010),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        if (coinsEarned > 0) "Reward added to your wallet" else "This level was already rewarded",
                        color = Color(0xFF9A7431),
                        fontSize = 9.sp
                    )
                }

                Box(
                    Modifier
                        .background(Color.White.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                ) {
                    Text(
                        "$coinBalance",
                        color = Color(0xFF5C431B),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            // ---- optional rewarded bonus (player-initiated) ----
            if (bonusAvailable) {
                Spacer(Modifier.height(8.dp))
                AdBonusPill(text = "WATCH AD  •  +$coinsEarned BONUS COINS", onClick = onBonus)
            }

            Spacer(Modifier.height(12.dp))

            Win3DButton(
                text = "NEXT LEVEL",
                icon = "→",
                primary = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = onNext
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Win3DButton("HOME", "⌂", false, Modifier.weight(1f), onMap)
                Win3DButton("RETRY", "↻", false, Modifier.weight(1f), onRetry)
            }
        }
    }
}

@Composable
private fun AdBonusPill(text: String, onClick: () -> Unit) {

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(100),
        label = "adPillScale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF6687FF), Color(0xFF3E60E8))),
                RoundedCornerShape(14.dp)
            )
            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text("▶", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.4.sp
        )
    }
}

@Composable
private fun Win3DButton(
    text: String,
    icon: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = tween(100, easing = FastOutSlowInEasing),
        label = "premiumButtonScale"
    )
    val yOffset = if (pressed) 3.dp else 0.dp

    val top = if (primary) Color(0xFF32D2DE) else Color(0xFFFFFEFA)
    val middle = if (primary) Color(0xFF10B5C5) else Color(0xFFF5F0E8)
    val bottom = if (primary) Color(0xFF07889A) else Color(0xFFD5CABB)
    val textColor = if (primary) Color.White else Color(0xFF5B4936)

    Box(
        modifier = modifier
            .height(if (primary) 56.dp else 49.dp)
            .offset(y = yOffset)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .shadow(
                elevation = if (pressed) 3.dp else 8.dp,
                shape = RoundedCornerShape(16.dp),
                clip = false,
                ambientColor = if (primary) Color(0xFF00889A).copy(alpha = 0.35f) else Color(0xFF8E7A62).copy(alpha = 0.25f),
                spotColor = if (primary) Color(0xFF00889A).copy(alpha = 0.38f) else Color(0xFF8E7A62).copy(alpha = 0.25f)
            )
            .background(bottom, RoundedCornerShape(16.dp))
            .padding(bottom = 3.dp)
            .background(Brush.verticalGradient(listOf(top, middle)), RoundedCornerShape(16.dp))
            .border(
                1.dp,
                if (primary) Color.White.copy(alpha = 0.60f) else Color(0xFFD0C4B3),
                RoundedCornerShape(16.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.76f)
                .height(8.dp)
                .align(Alignment.TopCenter)
                .background(
                    Color.White.copy(alpha = if (primary) 0.24f else 0.65f),
                    RoundedCornerShape(50)
                )
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(icon, color = textColor, fontSize = if (primary) 22.sp else 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(7.dp))
            Text(
                text,
                color = textColor,
                fontSize = if (primary) 13.sp else 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
private fun WinSparkles(progress: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val points = listOf(
            Triple(0.10f, 0.20f, 0.75f), Triple(0.20f, 0.38f, 1.00f),
            Triple(0.82f, 0.18f, 0.90f), Triple(0.91f, 0.42f, 0.70f),
            Triple(0.12f, 0.72f, 0.55f), Triple(0.88f, 0.74f, 0.85f),
            Triple(0.50f, 0.12f, 0.65f), Triple(0.34f, 0.84f, 0.50f),
            Triple(0.68f, 0.84f, 0.65f)
        )
        points.forEachIndexed { index, point ->
            val phase = (progress + index * 0.13f) % 1f
            val wave = 0.5f + 0.5f * kotlin.math.sin(phase * 6.28318f)
            val alpha = (0.18f + 0.82f * wave).coerceIn(0.08f, 1f) * point.third
            val radius = 2.1f + 2.3f * wave
            val center = Offset(size.width * point.first, size.height * point.second)
            drawCircle(Color(0xFFFFC928).copy(alpha = alpha), radius, center)
            drawLine(
                Color.White.copy(alpha = alpha * 0.72f),
                Offset(center.x - radius * 2.1f, center.y),
                Offset(center.x + radius * 2.1f, center.y),
                1.dp.toPx(), StrokeCap.Round
            )
            drawLine(
                Color.White.copy(alpha = alpha * 0.72f),
                Offset(center.x, center.y - radius * 2.1f),
                Offset(center.x, center.y + radius * 2.1f),
                1.dp.toPx(), StrokeCap.Round
            )
        }
    }
}

@Composable
private fun WinFallingParticles(progress: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val particles = listOf(
            Triple(0.05f, 0.03f, 0.95f), Triple(0.13f, 0.20f, 1.20f), Triple(0.22f, 0.08f, 0.82f),
            Triple(0.31f, 0.28f, 1.05f), Triple(0.41f, 0.02f, 0.90f), Triple(0.52f, 0.16f, 1.18f),
            Triple(0.63f, 0.05f, 0.88f), Triple(0.73f, 0.24f, 1.10f), Triple(0.84f, 0.08f, 0.96f),
            Triple(0.94f, 0.18f, 1.25f), Triple(0.17f, 0.55f, 0.78f), Triple(0.78f, 0.58f, 0.86f)
        )

        particles.forEachIndexed { index, p ->
            val t = (progress * p.third + index * 0.071f) % 1f
            val x = size.width * (p.first + kotlin.math.sin((t + index) * 6.28f) * 0.018f)
            val y = -18f + (size.height + 38f) * t
            val s = 4f + (index % 3) * 1.8f
            val alpha = (1f - t * 0.82f).coerceIn(0.12f, 1f)
            val angle = ((t * 360f) + index * 37f) % 360f
            val color = when (index % 5) {
                0 -> Color(0xFFFFC928)
                1 -> Color(0xFFFF5967)
                2 -> Color(0xFF31C8D5)
                3 -> Color(0xFF7A63E8)
                else -> Color(0xFF58C96B)
            }.copy(alpha = alpha)

            withTransform({ rotate(angle, Offset(x, y)) }) {
                drawRoundRect(
                    color,
                    topLeft = Offset(x - s / 2f, y - s),
                    size = Size(s, s * 1.9f),
                    cornerRadius = CornerRadius(1.8f, 1.8f)
                )
            }
        }
    }
}

@Composable
private fun PremiumAnimatedStars(stars: Int) {

    val transition = rememberInfiniteTransition(label = "starPulse")

    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            tween(720, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "starPulseValue"
    )

    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { index ->

            val earned = index < stars
            val scale = remember { Animatable(0.25f) }
            val rotation = remember { Animatable(-35f) }

            LaunchedEffect(stars) {
                delay(130L + index * 180L)
                scale.animateTo(
                    1f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                )
                rotation.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
            }

            Canvas(
                Modifier
                    .size(43.dp)
                    .graphicsLayer(
                        scaleX = if (earned) scale.value * pulse else scale.value,
                        scaleY = if (earned) scale.value * pulse else scale.value,
                        rotationZ = rotation.value
                    )
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val outer = size.minDimension * 0.43f
                val inner = outer * 0.46f
                val path = Path()

                for (i in 0 until 10) {
                    val radius = if (i % 2 == 0) outer else inner
                    val angle = -Math.PI / 2.0 + i * Math.PI / 5.0
                    val x = cx + kotlin.math.cos(angle).toFloat() * radius
                    val y = cy + kotlin.math.sin(angle).toFloat() * radius
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()

                if (earned) {
                    drawCircle(Color(0xFFFFC928).copy(alpha = 0.16f), outer * 1.20f)
                    drawPath(
                        path,
                        brush = Brush.verticalGradient(
                            listOf(Color(0xFFFFF29A), Color(0xFFFFC21C), Color(0xFFE99500))
                        )
                    )
                    drawPath(path, Color.White.copy(alpha = 0.48f), style = Stroke(1.2.dp.toPx()))
                    drawCircle(
                        Color.White.copy(alpha = 0.60f),
                        outer * 0.12f,
                        Offset(cx - outer * 0.30f, cy - outer * 0.25f)
                    )
                } else {
                    drawPath(path, Color(0xFFD7D1C7))
                    drawPath(path, Color.White.copy(alpha = 0.65f), style = Stroke(1.1.dp.toPx()))
                }
            }
        }
    }
}

// ============================================================
// GAME OVER
// The rewarded button is enabled only when an ad is ready and is the
// single "continue" path (engine.reviveAfterRewarded).
// ============================================================

@Composable
private fun GameOverOverlay(
    level: Int,
    adReady: Boolean,
    onWatchAd: () -> Unit,
    onRestart: () -> Unit,
    onMap: () -> Unit
) {

    val entrance = remember { Animatable(0f) }
    val cardOffset = remember { Animatable(34f) }
    val iconScale = remember { Animatable(0.70f) }
    val iconRotation = remember { Animatable(-10f) }

    val transition = rememberInfiniteTransition(label = "gameOverMotion")

    val pulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "gameOverPulse"
    )

    LaunchedEffect(Unit) {
        launch {
            entrance.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
            )
        }
        launch { cardOffset.animateTo(0f, tween(460, easing = FastOutSlowInEasing)) }
        launch {
            delay(80L)
            iconScale.animateTo(
                1.10f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
            )
            iconScale.animateTo(1f, tween(180))
        }
        launch {
            delay(80L)
            iconRotation.animateTo(7f, tween(130))
            iconRotation.animateTo(-4f, tween(100))
            iconRotation.animateTo(0f, tween(120))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(
                        Color(0xFFFFE5E7).copy(alpha = 0.98f),
                        Color(0xFFF7F2F1).copy(alpha = 0.99f),
                        Color(0xFFEDE8E6).copy(alpha = 0.99f)
                    )
                )
            )
            .blockTouches()
    ) {

        GameOverParticles()

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.88f)
                .graphicsLayer(
                    scaleX = entrance.value,
                    scaleY = entrance.value,
                    alpha = entrance.value,
                    translationY = cardOffset.value
                )
                .shadow(
                    elevation = 20.dp,
                    shape = RoundedCornerShape(26.dp),
                    clip = false,
                    ambientColor = Color(0xFFE84C5B).copy(alpha = 0.20f),
                    spotColor = Color(0xFFE84C5B).copy(alpha = 0.18f)
                )
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White, Color(0xFFFFF8F7), Color(0xFFF8EEEE))
                    ),
                    RoundedCornerShape(26.dp)
                )
                .border(1.dp, Color(0xFFF0D4D6), RoundedCornerShape(26.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Box(
                modifier = Modifier
                    .background(Color(0xFFFF5665).copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFFFF5665).copy(alpha = 0.16f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 11.dp, vertical = 5.dp)
            ) {
                Text(
                    "TRY AGAIN",
                    color = Color(0xFFE84C5B),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .size(78.dp)
                    .graphicsLayer(
                        scaleX = iconScale.value * pulse,
                        scaleY = iconScale.value * pulse,
                        rotationZ = iconRotation.value
                    )
                    .shadow(9.dp, CircleShape, clip = false)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFFFFA2AA), Color(0xFFFF6875), Color(0xFFE84C5B))
                        ),
                        CircleShape
                    )
                    .border(2.dp, Color.White.copy(alpha = 0.78f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("💔", fontSize = 35.sp)
            }

            Spacer(Modifier.height(9.dp))

            Text(
                "GAME OVER",
                color = Color(0xFF202733),
                fontSize = 25.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.7.sp
            )

            Spacer(Modifier.height(4.dp))

            Text(
                "LEVEL $level",
                color = Color(0xFF8A6A6D),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp
            )

            Spacer(Modifier.height(13.dp))

            GameOverRewardButton(adReady = adReady, onClick = onWatchAd)

            Spacer(Modifier.height(6.dp))

            Text(
                text = if (adReady) "WATCH A SHORT AD  •  GET 3 HEARTS  •  KEEP YOUR PROGRESS"
                else "AD LOADING…",
                color = Color(0xFF7B8491),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.25.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SoftPillButton("↻", "RESTART", Modifier.weight(1f), onRestart)
                SoftPillButton("⌂", "HOME", Modifier.weight(1f), onMap)
            }
        }
    }
}

@Composable
private fun GameOverParticles() {

    val transition = rememberInfiniteTransition(label = "gameOverParticles")

    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2700, easing = LinearEasing), RepeatMode.Restart),
        label = "gameOverParticleProgress"
    )

    Canvas(Modifier.fillMaxSize()) {
        val pieces = listOf(
            Triple(0.07f, 0.00f, 0), Triple(0.16f, 0.20f, 1), Triple(0.28f, 0.06f, 2),
            Triple(0.39f, 0.27f, 3), Triple(0.51f, 0.10f, 0), Triple(0.63f, 0.31f, 1),
            Triple(0.75f, 0.05f, 2), Triple(0.87f, 0.22f, 3), Triple(0.95f, 0.03f, 0),
            Triple(0.11f, 0.48f, 1), Triple(0.91f, 0.52f, 2)
        )

        pieces.forEachIndexed { index, piece ->
            val local = (progress + index * 0.087f) % 1f
            val y = -40f + size.height * (local * 1.12f)
            val sway = kotlin.math.sin((local * 6.28318f) + index) * 18f
            val x = size.width * piece.first + sway
            val alpha = when {
                local < 0.08f -> local / 0.08f
                local > 0.88f -> (1f - local) / 0.12f
                else -> 0.78f
            }.coerceIn(0f, 0.82f)
            val s = 5f + (index % 3) * 2f

            val color = when (piece.third) {
                0 -> Color(0xFFFF5665)
                1 -> Color(0xFFFFC43D)
                2 -> Color(0xFF8B72FF)
                else -> Color(0xFF4BCB69)
            }.copy(alpha = alpha)

            withTransform({
                rotate(local * 360f * if (index % 2 == 0) 1f else -1f, Offset(x, y))
            }) {
                drawRoundRect(
                    color,
                    topLeft = Offset(x, y),
                    size = Size(s, s * 1.8f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }
        }
    }
}

@Composable
private fun GameOverRewardButton(adReady: Boolean, onClick: () -> Unit) {

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val pulseTransition = rememberInfiniteTransition(label = "adButtonPulse")

    val glow by pulseTransition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.34f,
        animationSpec = infiniteRepeatable(
            tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "adButtonGlow"
    )

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = tween(100, easing = FastOutSlowInEasing),
        label = "adButtonScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .shadow(
                elevation = if (pressed) 2.dp else 10.dp,
                shape = RoundedCornerShape(18.dp),
                clip = false,
                ambientColor = Color(0xFF4F72FF).copy(alpha = glow),
                spotColor = Color(0xFF4F72FF).copy(alpha = glow)
            )
            .background(
                if (adReady) {
                    Brush.verticalGradient(
                        listOf(Color(0xFF6687FF), Color(0xFF3E60E8), Color(0xFF304FC5))
                    )
                } else {
                    Brush.verticalGradient(listOf(Color(0xFFBFC6D1), Color(0xFFA8B0BC)))
                },
                RoundedCornerShape(18.dp)
            )
            .border(
                1.dp,
                Color.White.copy(alpha = if (adReady) 0.45f else 0.18f),
                RoundedCornerShape(18.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = adReady,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {

            Text(
                if (adReady) "▶" else "…",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Black
            )

            Spacer(Modifier.width(9.dp))

            Column(horizontalAlignment = Alignment.Start) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (adReady) "CONTINUE LEVEL" else "AD LOADING",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.45.sp
                    )
                    if (adReady) {
                        Spacer(Modifier.width(8.dp))
                        Text("♥ +3", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    }
                }
                if (adReady) {
                    Text(
                        "VIDEO  • WATCH AD",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.45.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SoftPillButton(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(100),
        label = "pillScale"
    )

    Box(
        modifier = modifier
            .height(42.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .background(
                Brush.verticalGradient(listOf(Color.White, Color(0xFFF0ECE7))),
                RoundedCornerShape(13.dp)
            )
            .border(1.dp, Color(0xFFDAD1C8), RoundedCornerShape(13.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(icon, color = Color(0xFF66584E), fontSize = 17.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                color = Color(0xFF66584E),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.4.sp
            )
        }
    }
}

// ============================================================
// LEVEL PICKER
// ============================================================

@Composable
private fun LevelPickerOverlay(
    progress: ProgressStore,
    levels: LevelRepository,
    current: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {

    val unlocked = progress.highestUnlocked()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEFFFFFFF))
            .blockTouches(),
        contentAlignment = Alignment.Center
    ) {

        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f)
                .background(Color.White, RoundedCornerShape(25.dp))
                .border(1.dp, Color(0xFFE0E4EA), RoundedCornerShape(25.dp))
                .padding(17.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {

                Column {
                    Text(
                        "SELECT LEVEL",
                        color = Color(0xFF202733),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "Unlocked: $unlocked / 1000",
                        color = Color(0xFF7A8492),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                HeaderButton(onClick = onDismiss) { CloseXIcon(tint = Color(0xFF27303D)) }
            }

            Spacer(Modifier.height(10.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                items(count = 1000) { index ->

                    val level = index + 1
                    val enabled = level <= unlocked && levels.exists(level)
                    val isCurrent = level == current

                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(
                                when {
                                    isCurrent -> Color(0xFF4BCB69)
                                    enabled -> Color(0xFFF2F4F7)
                                    else -> Color(0xFFF7F8FA)
                                },
                                CircleShape
                            )
                            .border(
                                width = if (isCurrent) 2.dp else 1.dp,
                                color = if (isCurrent) Color(0xFF3AB95B) else Color(0xFFE0E4EA),
                                shape = CircleShape
                            )
                            .then(
                                if (enabled) Modifier.clickable { onSelect(level) }
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (enabled) level.toString() else "🔒",
                            color = if (isCurrent) Color.White else Color(0xFF46515F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
