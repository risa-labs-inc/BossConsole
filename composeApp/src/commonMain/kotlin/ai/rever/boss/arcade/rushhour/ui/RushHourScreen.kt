@file:Suppress("LongMethod", "CyclomaticComplexMethod", "MaxLineLength")

package ai.rever.boss.arcade.rushhour.ui

import ai.rever.boss.arcade.rushhour.model.RushHourBoard
import ai.rever.boss.arcade.rushhour.model.RushHourDragMath
import ai.rever.boss.arcade.rushhour.model.RushHourEngine
import ai.rever.boss.arcade.rushhour.model.RushHourGameState
import ai.rever.boss.arcade.rushhour.model.RushHourSolver
import ai.rever.boss.arcade.rushhour.model.RushHourVehicle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Interactive Boss Arcade: Rush Hour Gym Screen.
 * Overhauled to Apple Human Interface Guidelines (HIG) with continuous squircle radii,
 * constrained 1D drag & snap physics, dynamic aspect-ratio container scaling, and rule guide modal.
 */
@Composable
fun RushHourScreen(modifier: Modifier = Modifier) {
    val snapshot by RushHourGameState.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showHelpSheet by remember { mutableStateOf(false) }

    val optimalityScore =
        snapshot.trajectorySummary?.efficiencyPercentage
            ?: RushHourSolver.calculateOptimalityScore(
                initialOptimalDistance = snapshot.initialOptimalDistance,
                stepsTaken = snapshot.stepsTaken,
            )

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(RushHourTheme.BoardBackdrop)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.One -> {
                                scope.launch { RushHourGameState.reset(1) }
                                true
                            }

                            Key.Two -> {
                                scope.launch { RushHourGameState.reset(2) }
                                true
                            }

                            Key.Three -> {
                                scope.launch { RushHourGameState.reset(3) }
                                true
                            }

                            Key.Four -> {
                                scope.launch { RushHourGameState.reset(4) }
                                true
                            }

                            Key.R -> {
                                scope.launch { RushHourGameState.reset(snapshot.level) }
                                true
                            }

                            Key.Escape -> {
                                if (showHelpSheet) {
                                    showHelpSheet = false
                                    true
                                } else {
                                    false
                                }
                            }

                            else -> {
                                false
                            }
                        }
                    } else {
                        false
                    }
                },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top HUD Header
            RushHourAppleHeader(
                level = snapshot.level,
                stepsTaken = snapshot.stepsTaken,
                optimalRemaining = snapshot.optimalDistanceRemaining,
                optimalityScore = optimalityScore,
                isDeadlocked = snapshot.isDeadlocked,
                onSelectLevel = { lvl ->
                    scope.launch { RushHourGameState.reset(lvl) }
                },
                onReset = {
                    scope.launch { RushHourGameState.reset(snapshot.level) }
                },
                onOpenHelp = {
                    showHelpSheet = true
                },
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Solved or Deadlocked Status Banner
            AnimatedVisibility(
                visible = snapshot.isSolved || snapshot.isDeadlocked,
                enter = fadeIn(tween(250)),
                exit = fadeOut(tween(200)),
            ) {
                if (snapshot.isSolved) {
                    VictoryBannerApple(
                        summary = snapshot.trajectorySummary,
                        onNextLevel = {
                            val nextLevel = if (snapshot.level < 4) snapshot.level + 1 else 1
                            scope.launch { RushHourGameState.reset(nextLevel) }
                        },
                    )
                } else if (snapshot.isDeadlocked) {
                    DeadlockBannerApple(
                        onReset = {
                            scope.launch { RushHourGameState.reset(snapshot.level) }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Dynamic Aspect-Ratio Board Arena
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                RushHourBoardArena(
                    board = snapshot.board,
                    selectedVehicleId = snapshot.selectedVehicleId,
                    onSelectVehicle = { id ->
                        scope.launch { RushHourGameState.selectVehicle(id) }
                    },
                    onMoveVehicle = { id, steps ->
                        RushHourGameState.move(id, steps)
                        Unit
                    },
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tactile Vehicle Control Bar (Accessible Click Alternative)
            val selected = snapshot.selectedVehicleId?.let { snapshot.board.getVehicle(it) }
            if (selected != null) {
                VehicleControlBarApple(
                    vehicle = selected,
                    onMove = { steps ->
                        scope.launch { RushHourGameState.move(selected.id, steps) }
                    },
                )
            } else {
                Spacer(modifier = Modifier.height(44.dp))
            }
        }

        // Help & Instructions Modal Sheet
        if (showHelpSheet) {
            RushHourHelpSheet(
                onDismiss = { showHelpSheet = false },
            )
        }
    }
}

@Composable
private fun RushHourAppleHeader(
    level: Int,
    stepsTaken: Int,
    optimalRemaining: Int?,
    optimalityScore: Double,
    isDeadlocked: Boolean,
    onSelectLevel: (Int) -> Unit,
    onReset: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RushHourTheme.CardShape)
                .background(RushHourTheme.CardSurface)
                .border(1.dp, RushHourTheme.CardBorder, RushHourTheme.CardShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column {
            // Row 1: App Title & Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(RushHourTheme.PrimaryCarStart),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "RUSH HOUR GYM",
                            color = RushHourTheme.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        )
                        Text(
                            text = "Track 01 Agent Gym & Benchmark",
                            color = RushHourTheme.TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Help Button (?) with 44x44 minimum touch target
                    Box(
                        modifier =
                            Modifier
                                .size(36.dp)
                                .clip(RushHourTheme.PillShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { onOpenHelp() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "Instructions & Rules",
                            tint = RushHourTheme.TextPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    // Reset Button
                    Box(
                        modifier =
                            Modifier
                                .clip(RushHourTheme.PillShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { onReset() }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset Level",
                                tint = RushHourTheme.TextPrimary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Reset",
                                color = RushHourTheme.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 2: Apple Segmented Level Selector
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RushHourTheme.PillShape)
                        .background(RushHourTheme.CellSurface)
                        .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val levels =
                    listOf(
                        1 to "Level 1",
                        2 to "Level 2",
                        3 to "Level 3",
                        4 to "Level 4",
                    )
                levels.forEach { (lvl, title) ->
                    val isSelected = lvl == level
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(RushHourTheme.PillShape)
                                .background(
                                    if (isSelected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                                ).clickable { onSelectLevel(lvl) }
                                .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) RushHourTheme.TextPrimary else RushHourTheme.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 3: Metrics Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppleStatPill(
                    label = "MOVES",
                    value = stepsTaken.toString(),
                    valueColor = RushHourTheme.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                AppleStatPill(
                    label = "OPTIMAL (d*)",
                    value =
                        when {
                            optimalRemaining == null -> "Deadlock"
                            optimalRemaining == 0 -> "Solved!"
                            else -> "$optimalRemaining"
                        },
                    valueColor =
                        when {
                            isDeadlocked -> RushHourTheme.WarningRed
                            optimalRemaining == 0 -> RushHourTheme.ExitEmerald
                            else -> RushHourTheme.SelectedBorder
                        },
                    modifier = Modifier.weight(1f),
                )
                AppleStatPill(
                    label = "EFFICIENCY (η)",
                    value = "${optimalityScore.roundToInt()}%",
                    valueColor =
                        when {
                            optimalityScore >= 80.0 -> RushHourTheme.ExitEmerald
                            optimalityScore >= 50.0 -> RushHourTheme.PrimaryCarEnd
                            else -> RushHourTheme.WarningRed
                        },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AppleStatPill(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RushHourTheme.CardShape)
                .background(RushHourTheme.CellSurface)
                .border(1.dp, RushHourTheme.CellBorder, RushHourTheme.CardShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                color = RushHourTheme.TextTertiary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = valueColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private data class DragPreview(
    val vehicle: RushHourVehicle,
    val projectedStep: Int,
)

@Composable
private fun RushHourBoardArena(
    board: RushHourBoard,
    selectedVehicleId: String?,
    onSelectVehicle: (String) -> Unit,
    onMoveVehicle: suspend (String, Int) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val paddingTotal = 16.dp
        val exitSignGutter = 58.dp
        val maxAvailableWidth = maxWidth - paddingTotal - exitSignGutter
        val maxAvailableHeight = maxHeight - paddingTotal
        val boardSize = minOf(maxAvailableWidth, maxAvailableHeight).coerceIn(240.dp, 440.dp)
        val borderThickness = 1.dp
        val innerPadding = 6.dp
        val blockInset = 3.dp

        // Exact square area for 6x6 grid
        val gridAreaSize = boardSize - (innerPadding * 2) - (borderThickness * 2)
        val cellSize = gridAreaSize / RushHourBoard.GRID_SIZE.toFloat()
        val cellSizePx = with(density) { cellSize.toPx() }

        // Continuous subtle pulsing glow for exit markers
        val infiniteTransition = rememberInfiniteTransition()
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.30f,
            targetValue = 0.85f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
        )

        var activeDragPreview by remember { mutableStateOf<DragPreview?>(null) }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // 1. 6x6 Board Container
            Box(
                modifier =
                    Modifier
                        .size(boardSize)
                        .shadow(16.dp, shape = RushHourTheme.BoardShape, spotColor = Color.Black.copy(alpha = 0.5f))
                        .clip(RushHourTheme.BoardShape)
                        .background(RushHourTheme.BoardSurface)
                        .border(borderThickness, RushHourTheme.BoardBorder, RushHourTheme.BoardShape)
                        .padding(innerPadding),
            ) {
                // Background 6x6 Grid Cells (Recessed Squircle Wells)
                Column(modifier = Modifier.fillMaxSize()) {
                    for (r in 0 until RushHourBoard.GRID_SIZE) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(cellSize),
                        ) {
                            for (c in 0 until RushHourBoard.GRID_SIZE) {
                                val isExitLane = r == RushHourBoard.EXIT_ROW && c == RushHourBoard.EXIT_COL

                                Box(
                                    modifier =
                                        Modifier
                                            .size(cellSize)
                                            .padding(blockInset)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isExitLane) {
                                                    Color(0xFF10B981).copy(alpha = pulseAlpha * 0.15f)
                                                } else {
                                                    Color(0xFF0D1117)
                                                },
                                            ).border(
                                                width = 1.dp,
                                                color =
                                                    if (isExitLane) {
                                                        Color(0xFF10B981).copy(alpha = pulseAlpha * 0.5f)
                                                    } else {
                                                        Color.White.copy(alpha = 0.04f)
                                                    },
                                                shape = RoundedCornerShape(8.dp),
                                            ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isExitLane) {
                                        Text(
                                            text = "➔",
                                            color = Color(0xFF10B981).copy(alpha = pulseAlpha * 0.7f),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Projected Destination Highlight Layer (Beneath dragging vehicle)
                activeDragPreview?.let { preview ->
                    if (preview.projectedStep != 0) {
                        val pv = preview.vehicle
                        val (destRow, destCol) = RushHourDragMath.computeProjectedCoordinates(pv, preview.projectedStep)
                        val destX = (cellSize * destCol) + blockInset
                        val destY = (cellSize * destRow) + blockInset
                        val destWidth = (if (pv.isHorizontal) cellSize * pv.length else cellSize) - (blockInset * 2)
                        val destHeight = (if (!pv.isHorizontal) cellSize * pv.length else cellSize) - (blockInset * 2)

                        Box(
                            modifier =
                                Modifier
                                    .offset(x = destX, y = destY)
                                    .size(destWidth, destHeight)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x3310B981))
                                    .border(1.5.dp, Color(0xFF10B981), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (pv.id == RushHourBoard.TARGET_VEHICLE_ID && destCol == 4) "EXIT ➔" else "DROP TARGET",
                                color = Color(0xFF10B981),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp,
                            )
                        }
                    }
                }

                // 3. Interactive Vehicles Layer
                for (v in board.vehicles) {
                    VehiclePiece(
                        vehicle = v,
                        board = board,
                        cellSize = cellSize,
                        cellSizePx = cellSizePx,
                        blockInset = blockInset,
                        isSelected = v.id == selectedVehicleId,
                        onSelect = { onSelectVehicle(v.id) },
                        onMove = { steps -> onMoveVehicle(v.id, steps) },
                        onDragPreview = { preview -> activeDragPreview = preview },
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 4. Exterior Illuminated Exit Gate (Outside 6x6 matrix, aligned with Row 2)
            Box(
                modifier =
                    Modifier
                        .height(boardSize)
                        .width(exitSignGutter - 6.dp),
            ) {
                val exitGateHeight = 34.dp
                val exitRowTop = innerPadding + (cellSize * RushHourBoard.EXIT_ROW)
                val exitGateY = exitRowTop + (cellSize - exitGateHeight) / 2

                Box(
                    modifier =
                        Modifier
                            .offset(y = exitGateY)
                            .fillMaxWidth()
                            .height(exitGateHeight)
                            .shadow(
                                elevation = 10.dp,
                                shape = RoundedCornerShape(8.dp),
                                spotColor = Color(0xFF10B981).copy(alpha = pulseAlpha * 0.7f),
                                ambientColor = Color(0xFF10B981).copy(alpha = pulseAlpha * 0.4f),
                            ).clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.12f + pulseAlpha * 0.14f))
                            .border(
                                width = 1.dp,
                                color = Color(0xFF10B981).copy(alpha = 0.4f + pulseAlpha * 0.4f),
                                shape = RoundedCornerShape(8.dp),
                            ).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "EXIT",
                            color = Color(0xFF10B981),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp,
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "➔",
                            color = Color(0xFF10B981),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VehiclePiece(
    vehicle: RushHourVehicle,
    board: RushHourBoard,
    cellSize: Dp,
    cellSizePx: Float,
    blockInset: Dp,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onMove: suspend (Int) -> Unit,
    onDragPreview: (DragPreview?) -> Unit,
) {
    val isTarget = vehicle.id == RushHourBoard.TARGET_VEHICLE_ID

    // Calculate dynamic sliding limits for 1D drag clamping
    val (minSteps, maxSteps) =
        remember(board, vehicle.id) {
            RushHourEngine.computeSlidingLimits(board, vehicle.id)
        }

    val dragAnimatable = remember(vehicle.id) { Animatable(0f) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var isDragging by remember { mutableStateOf(false) }
    var isSettling by remember { mutableStateOf(false) }
    var cumulativeDragX by remember { mutableStateOf(0f) }
    var cumulativeDragY by remember { mutableStateOf(0f) }

    val visualDragOffsetDp = with(density) { dragAnimatable.value.toDp() }

    val cardWidth = if (vehicle.isHorizontal) (cellSize * vehicle.length) - (blockInset * 2) else cellSize - (blockInset * 2)
    val cardHeight = if (vehicle.isHorizontal) cellSize - (blockInset * 2) else (cellSize * vehicle.length) - (blockInset * 2)

    val baseOffsetX = (cellSize * vehicle.col) + blockInset
    val baseOffsetY = (cellSize * vehicle.row) + blockInset

    val offsetX = if (vehicle.isHorizontal) baseOffsetX + visualDragOffsetDp else baseOffsetX
    val offsetY = if (!vehicle.isHorizontal) baseOffsetY + visualDragOffsetDp else baseOffsetY

    // Real-time projected destination highlight signal
    LaunchedEffect(isDragging, dragAnimatable.value) {
        if (isDragging) {
            val step =
                RushHourDragMath.computeProjectedStep(
                    clampedOffset = dragAnimatable.value,
                    cellSizePx = cellSizePx,
                    minSteps = minSteps,
                    maxSteps = maxSteps,
                )
            onDragPreview(if (step != 0) DragPreview(vehicle, step) else null)
        } else {
            onDragPreview(null)
        }
    }

    DisposableEffect(vehicle.id) {
        onDispose {
            onDragPreview(null)
        }
    }

    val scaleX = if (isDragging && vehicle.isHorizontal) 1.02f else 1.0f
    val scaleY = if (isDragging && !vehicle.isHorizontal) 1.02f else 1.0f

    Box(
        modifier =
            Modifier
                .offset(x = offsetX, y = offsetY)
                .size(cardWidth, cardHeight)
                .scale(scaleX, scaleY)
                .shadow(
                    elevation = if (isDragging) 10.dp else 2.dp,
                    shape = RoundedCornerShape(10.dp),
                    spotColor = if (isSelected) RushHourTheme.SelectedGlow else Color.Black.copy(alpha = 0.5f),
                ).clip(RoundedCornerShape(10.dp))
                .background(
                    when {
                        isTarget -> RushHourTheme.PrimaryCarBrush
                        vehicle.length == 3 -> RushHourTheme.TruckBrush
                        else -> RushHourTheme.CarBrush
                    },
                ).border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color =
                        when {
                            isSelected -> RushHourTheme.SelectedBorder
                            isTarget -> RushHourTheme.PrimaryCarBorder
                            else -> RushHourTheme.VehicleBorder
                        },
                    shape = RoundedCornerShape(10.dp),
                ).clickable { onSelect() }
                .pointerInput(vehicle.id, board, cellSizePx) {
                    detectDragGestures(
                        onDragStart = {
                            if (isSettling) return@detectDragGestures
                            isDragging = true
                            cumulativeDragX = 0f
                            cumulativeDragY = 0f
                            onSelect()
                        },
                        onDragEnd = {
                            if (isSettling) return@detectDragGestures
                            isDragging = false
                            val currentOffset = dragAnimatable.value
                            val step =
                                RushHourDragMath.computeSnapStep(
                                    clampedOffset = currentOffset,
                                    cellSizePx = cellSizePx,
                                    thresholdFraction = RushHourDragMath.COMMIT_THRESHOLD_FRACTION,
                                    minSteps = minSteps,
                                    maxSteps = maxSteps,
                                )

                            scope.launch {
                                if (step != 0) {
                                    isSettling = true
                                    try {
                                        // 1. First animate smoothly to the exact target cell pixel offset
                                        dragAnimatable.animateTo(
                                            targetValue = step * cellSizePx,
                                            animationSpec =
                                                spring(
                                                    stiffness = Spring.StiffnessMediumLow,
                                                    dampingRatio = 0.85f,
                                                ),
                                        )
                                        // Commit before clearing the offset, then let the board
                                        // state render at its new coordinate on the next frame.
                                        onMove(step)
                                        withFrameNanos { }
                                        dragAnimatable.snapTo(0f)
                                    } finally {
                                        isSettling = false
                                    }
                                } else {
                                    isSettling = true
                                    try {
                                        // Snap back smoothly to 0f
                                        dragAnimatable.animateTo(
                                            targetValue = 0f,
                                            animationSpec =
                                                spring(
                                                    stiffness = Spring.StiffnessMediumLow,
                                                    dampingRatio = 0.85f,
                                                ),
                                        )
                                    } finally {
                                        isSettling = false
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            scope.launch {
                                dragAnimatable.animateTo(
                                    targetValue = 0f,
                                    animationSpec =
                                        spring(
                                            stiffness = Spring.StiffnessMediumLow,
                                            dampingRatio = 0.85f,
                                        ),
                                )
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (isSettling) return@detectDragGestures
                            change.consume()
                            cumulativeDragX += dragAmount.x
                            cumulativeDragY += dragAmount.y

                            val clamped =
                                RushHourDragMath.calculateConstrainedOffset(
                                    totalDragX = cumulativeDragX,
                                    totalDragY = cumulativeDragY,
                                    isHorizontal = vehicle.isHorizontal,
                                    cellSizePx = cellSizePx,
                                    minSteps = minSteps,
                                    maxSteps = maxSteps,
                                )
                            scope.launch {
                                dragAnimatable.snapTo(clamped)
                            }
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        // Subtle specular sheen line at the top
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopCenter)
                    .background(RushHourTheme.VehicleSpecular),
        )

        // Tactile Vehicle Indicators & Labels
        if (isTarget) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "CAR X",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "➔",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            // Blocking Car or Truck
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = vehicle.id,
                    color = RushHourTheme.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
                if (vehicle.length == 3) {
                    Text(
                        text = "TRUCK",
                        color = RushHourTheme.TextTertiary,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun VehicleControlBarApple(
    vehicle: RushHourVehicle,
    onMove: (Int) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RushHourTheme.PillShape)
                .background(RushHourTheme.CardSurface)
                .border(1.dp, RushHourTheme.CardBorder, RushHourTheme.PillShape)
                .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Vehicle '${vehicle.id}' Controls:",
                color = RushHourTheme.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )

            // Step Backward Button (Left or Up) - 44x36 min hit target
            Box(
                modifier =
                    Modifier
                        .size(width = 44.dp, height = 32.dp)
                        .clip(RushHourTheme.PillShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { onMove(-1) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (vehicle.isHorizontal) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.ArrowUpward,
                    contentDescription = if (vehicle.isHorizontal) "Step Left" else "Step Up",
                    tint = RushHourTheme.TextPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }

            // Step Forward Button (Right or Down) - 44x36 min hit target
            Box(
                modifier =
                    Modifier
                        .size(width = 44.dp, height = 32.dp)
                        .clip(RushHourTheme.PillShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { onMove(1) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (vehicle.isHorizontal) Icons.AutoMirrored.Filled.ArrowForward else Icons.Default.ArrowDownward,
                    contentDescription = if (vehicle.isHorizontal) "Step Right" else "Step Down",
                    tint = RushHourTheme.TextPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun VictoryBannerApple(
    summary: ai.rever.boss.arcade.rushhour.eval.TrajectorySummary?,
    onNextLevel: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth(0.9f)
                .clip(RushHourTheme.CardShape)
                .background(RushHourTheme.ExitEmerald.copy(alpha = 0.15f))
                .border(1.dp, RushHourTheme.ExitEmerald.copy(alpha = 0.4f), RushHourTheme.CardShape)
                .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Victory",
                    tint = RushHourTheme.ExitEmerald,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "SOLVED // PUZZLE COMPLETE",
                        color = RushHourTheme.ExitEmerald,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (summary != null) {
                        Text(
                            text =
                                "Moves: ${summary.totalMoves} • Optimal: ${summary.optimalMovesNeeded} • " +
                                    "Efficiency: ${summary.efficiencyPercentage.roundToInt()}%",
                            color = RushHourTheme.TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            Box(
                modifier =
                    Modifier
                        .clip(RushHourTheme.PillShape)
                        .background(RushHourTheme.ExitEmerald)
                        .clickable { onNextLevel() }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Next Level ➔",
                    color = RushHourTheme.BoardBackdrop,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun DeadlockBannerApple(onReset: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth(0.9f)
                .clip(RushHourTheme.CardShape)
                .background(RushHourTheme.WarningRed.copy(alpha = 0.15f))
                .border(1.dp, RushHourTheme.WarningRed.copy(alpha = 0.4f), RushHourTheme.CardShape)
                .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Deadlock",
                    tint = RushHourTheme.WarningRed,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "DEADLOCK DETECTED",
                        color = RushHourTheme.WarningRed,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "No path remains to the exit. Reset to try again.",
                        color = RushHourTheme.TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }

            Box(
                modifier =
                    Modifier
                        .clip(RushHourTheme.PillShape)
                        .background(RushHourTheme.WarningRed)
                        .clickable { onReset() }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Reset",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
