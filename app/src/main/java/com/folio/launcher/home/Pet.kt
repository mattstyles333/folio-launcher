package com.folio.launcher.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.folio.launcher.data.Door
import com.folio.launcher.data.PetMood
import com.folio.launcher.data.PetMove
import com.folio.launcher.data.PetSheet
import com.folio.launcher.data.PetUi
import com.folio.launcher.ui.PrintInk
import com.folio.launcher.ui.PrintShadow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

private val PET_W = 60.dp
private val PET_H = 65.dp
private const val DOOR_PAUSE_MS = 1_500L

/** The dog on the print: its line above, the collar tag below. Taps are a pat, never a print gesture. */
@Composable
fun PetOnPrint(
    pet: PetUi,
    saturation: Float,
    onPat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheet = pet.sheet ?: return
    val frame by rememberPetFrame(pet.mood, pet.move, pet.beat)
    Column(modifier) {
        AnimatedContent(
            targetState = pet.line,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(400)) },
            label = "petLine",
        ) { line ->
            if (line.isNotEmpty()) {
                Text(
                    text = line,
                    style = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 14.sp,
                        shadow = PrintShadow,
                    ),
                    color = PrintInk,
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .padding(bottom = 4.dp),
                )
            }
        }
        Box {
            PetSprite(
                sheet = sheet,
                move = frame.first,
                column = frame.second,
                saturation = saturation,
                modifier = Modifier
                    .size(PET_W, PET_H)
                    .testTag("pet")
                    .pointerInput(onPat) { detectTapGestures(onTap = { onPat() }) },
            )
            if (pet.mood == PetMood.Sleepy) {
                Text(
                    text = "z",
                    style = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, shadow = PrintShadow),
                    color = PrintInk.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        if (pet.tag.isNotEmpty()) {
            Text(
                text = pet.tag,
                style = TextStyle(fontSize = 10.sp, letterSpacing = 0.4.sp, shadow = PrintShadow),
                color = PrintInk.copy(alpha = 0.82f),
            )
        }
    }
}

/**
 * An endless app about to open: the dog looks up, today's minutes show, and after a
 * moment the choice appears. Tapping the dog, Stay, or Back keeps you home.
 */
@Composable
fun DoorPause(
    door: Door,
    sheet: PetSheet,
    accent: Color,
    onGo: () -> Unit,
    onStay: () -> Unit,
) {
    BackHandler(onBack = onStay)
    var ready by remember(door.app.key) { mutableStateOf(false) }
    LaunchedEffect(door.app.key) {
        delay(DOOR_PAUSE_MS)
        ready = true
    }
    val choice by animateFloatAsState(if (ready) 1f else 0f, tween(260), label = "doorChoice")
    val frame by rememberPetFrame(PetMood.Restless, PetMove.Review, beat = 0)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("door"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PetSprite(
                sheet = sheet,
                move = frame.first,
                column = frame.second,
                saturation = 1f,
                modifier = Modifier
                    .size(132.dp, 143.dp)
                    .testTag("doorPet")
                    .pointerInput(onStay) { detectTapGestures(onTap = { onStay() }) },
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = door.app.label,
                color = PrintInk,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Light,
                fontSize = 30.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(door.today, color = PrintInk.copy(alpha = 0.55f), fontSize = 14.sp)
            Spacer(Modifier.height(36.dp))
            Row(
                Modifier.alpha(choice),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Stay",
                    color = accent.copy(alpha = 0.95f),
                    fontSize = 17.sp,
                    modifier = Modifier.clickable(enabled = ready, onClick = onStay),
                )
                Text(
                    text = "Open ${door.app.label}",
                    color = PrintInk.copy(alpha = 0.55f),
                    fontSize = 17.sp,
                    modifier = Modifier.clickable(enabled = ready, onClick = onGo),
                )
            }
        }
    }
}

@Composable
private fun PetSprite(
    sheet: PetSheet,
    move: PetMove,
    column: Int,
    saturation: Float,
    modifier: Modifier = Modifier,
) {
    val filter = remember(saturation) {
        if (saturation >= 0.995f) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
    }
    val g = sheet.geometry
    Canvas(modifier) {
        drawImage(
            image = sheet.image,
            srcOffset = IntOffset(column.coerceIn(0, 7) * g.frameW, move.row.coerceAtMost(g.rows - 1) * g.frameH),
            srcSize = IntSize(g.frameW, g.frameH),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            colorFilter = filter,
        )
    }
}

/**
 * Plays [move] once as a reaction, then the mood's resting pose: breathing between long
 * holds, so the idle screen mostly isn't redrawing. Paused while Folio isn't visible.
 */
@Composable
private fun rememberPetFrame(mood: PetMood, move: PetMove, beat: Int): State<Pair<PetMove, Int>> {
    val frame = remember { mutableStateOf(PetMove.Idle to 0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(mood, move, beat) {
        var reacted = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            suspend fun play(m: PetMove, loops: Int = 1) {
                repeat(loops) {
                    m.holdsMs.forEachIndexed { col, hold ->
                        frame.value = m to col
                        delay(hold.toLong())
                    }
                }
            }
            suspend fun rest(pose: PetMove, column: Int, from: Long, until: Long) {
                frame.value = pose to column
                delay(Random.nextLong(from, until))
            }
            if (!reacted) {
                if (move != PetMove.Idle) play(move, loops = if (move.loopMs < 900) 3 else 2)
                reacted = true
            }
            while (true) {
                when (mood) {
                    PetMood.Sleepy -> {
                        frame.value = PetMove.Idle to 0
                        awaitCancellation()
                    }
                    PetMood.Mopey -> {
                        rest(PetMove.Failed, PetMove.Failed.lastColumn, 9_000, 15_000)
                        play(PetMove.Failed)
                    }
                    PetMood.Restless -> {
                        play(PetMove.Waiting, loops = 2)
                        rest(PetMove.Waiting, 0, 2_000, 5_000)
                    }
                    PetMood.Bright -> {
                        play(if (Random.nextInt(4) == 0) PetMove.Jumping else PetMove.Idle)
                        rest(PetMove.Idle, 0, 2_000, 5_000)
                    }
                    PetMood.Content -> {
                        play(PetMove.Idle)
                        rest(PetMove.Idle, 0, 3_000, 8_000)
                    }
                }
            }
        }
    }
    return frame
}
