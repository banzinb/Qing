package com.zhousl.aether.ui.pet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

private val DegreesToRadians = (PI / 180.0).toFloat()
private val RadiansToDegrees = (180.0 / PI).toFloat()

/**
 * Floating chat pet. Plays the matching animation row for the given mood,
 * supports tap-to-pet, and can hold a static 16-direction look frame.
 */
@Composable
fun QingPet(
    pet: PetDefinition,
    mood: QingPetMood,
    modifier: Modifier = Modifier,
    lookAngle: Float? = null,
    onPetted: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val spriteSheet = remember(pet.spriteAssetPath) {
        QingPetSpriteSheet.load(context, pet.spriteAssetPath)
    }
    val spec = remember(mood) { petMoodSpec(mood) }
    var frameIndex by remember(mood) { mutableIntStateOf(0) }
    val showingLook = lookAngle != null

    LaunchedEffect(mood, spec, showingLook) {
        if (showingLook) {
            frameIndex = 0
            return@LaunchedEffect
        }
        if (spec.loop) {
            while (true) {
                delay(spec.frameDurationMillis.toLong())
                frameIndex = (frameIndex + 1) % spec.frameCount
            }
        } else {
            frameIndex = 0
            while (frameIndex < spec.frameCount - 1) {
                delay(spec.frameDurationMillis.toLong())
                frameIndex += 1
            }
        }
    }

    val clickModifier = if (onPetted != null) {
        modifier.clickable(onClick = onPetted)
    } else {
        modifier
    }

    Canvas(
        modifier = clickModifier.aspectRatio(192f / 208f),
    ) {
        val sprite = spriteSheet
        if (sprite == null) {
            drawPlaceholder()
            return@Canvas
        }
        val sourceOffset = if (showingLook) {
            val (lookRow, lookCol) = petLookCell(lookAngle!!)
            IntOffset(
                x = lookCol * sprite.cellWidth,
                y = lookRow * sprite.cellHeight,
            )
        } else {
            val safeFrame = frameIndex.coerceIn(0, spec.frameCount - 1)
            IntOffset(
                x = safeFrame * sprite.cellWidth,
                y = spec.row * sprite.cellHeight,
            )
        }
        drawImage(
            image = sprite.image,
            srcOffset = sourceOffset,
            srcSize = IntSize(sprite.cellWidth, sprite.cellHeight),
            dstSize = IntSize(
                width = size.width.roundToInt(),
                height = size.height.roundToInt(),
            ),
            filterQuality = FilterQuality.Medium,
        )
    }
}

private fun DrawScope.drawPlaceholder() {
    drawCircle(
        color = Color(0x6622C55E),
        radius = size.minDimension / 2f,
    )
}

/**
 * Floating chat overlay: the pet with a level badge, a brief "pet" reaction bubble,
 * idle glancing at your taps / the input bar, and left-right pacing while tools run.
 */
@Composable
fun QingPetFloatingOverlay(
    pet: PetDefinition,
    mood: QingPetMood,
    level: Int,
    petCount: Long,
    modifier: Modifier = Modifier,
    lookTarget: Offset? = null,
    lookInput: Boolean = false,
    onPetted: (() -> Unit)? = null,
) {
    var petBubbleVisible by remember { mutableStateOf(false) }
    var lastPetCount by remember { mutableLongStateOf(petCount) }

    LaunchedEffect(petCount) {
        if (petCount > lastPetCount) {
            petBubbleVisible = true
            delay(1200)
            petBubbleVisible = false
        }
        lastPetCount = petCount
    }

    // Current pet center in window coordinates, for look-angle math.
    var petCenterGlobal by remember { mutableStateOf<Offset?>(null) }

    // External tap target (absolute window coords), shown for a short glance.
    var externalLook by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(lookTarget) {
        if (lookTarget != null) {
            externalLook = lookTarget
            delay(1600)
            externalLook = null
        }
    }

    // Idle glances: randomly look around every few seconds.
    var glanceAngle by remember { mutableFloatStateOf(0f) }
    var glancing by remember { mutableStateOf(false) }
    LaunchedEffect(mood) {
        if (mood == QingPetMood.Idle) {
            while (true) {
                delay(2400L + Random.nextInt(2600))
                glanceAngle = Random.nextFloat() * 360f
                glancing = true
                delay(1300L + Random.nextInt(700))
                glancing = false
            }
        } else {
            glancing = false
        }
    }

    val lookAngle: Float? = remember(
        petCenterGlobal,
        externalLook,
        glanceAngle,
        glancing,
        lookInput,
        mood,
    ) {
        val petCenter = petCenterGlobal ?: return@remember null
        val relative = when {
            externalLook != null -> externalLook!! - petCenter
            glancing -> {
                val angleRadians = glanceAngle * DegreesToRadians
                Offset(
                    x = cos(angleRadians) * 240f,
                    y = sin(angleRadians) * 240f,
                )
            }
            lookInput -> Offset(-240f, 60f)
            else -> return@remember null
        }
        val angle = atan2(relative.y, relative.x) * RadiansToDegrees
        ((angle % 360f) + 360f) % 360f
    }
    val effectiveLook = if (mood == QingPetMood.Idle) lookAngle else null

    // Pacing: while the agent runs tools, the pet paces left and right.
    val paceX = remember { Animatable(0f) }
    var pacingRight by remember { mutableStateOf(true) }
    LaunchedEffect(mood) {
        if (mood == QingPetMood.Running) {
            pacingRight = true
            while (true) {
                paceX.snapTo(0f)
                paceX.animateTo(20f, animationSpec = tween(800))
                pacingRight = false
                paceX.animateTo(-20f, animationSpec = tween(1600))
                pacingRight = true
                paceX.animateTo(0f, animationSpec = tween(800))
            }
        } else {
            paceX.snapTo(0f)
            pacingRight = true
        }
    }
    val displayMood = when {
        mood == QingPetMood.Running && pacingRight -> QingPetMood.RunningRight
        mood == QingPetMood.Running && !pacingRight -> QingPetMood.RunningLeft
        else -> mood
    }

    Box(
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (petBubbleVisible) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xCC1C1C1F))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "❤️",
                        fontSize = 12.sp,
                        color = Color(0xFFFF6B81),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .offset { IntOffset(paceX.value.roundToInt(), 0) }
                    .onGloballyPositioned { coordinates ->
                        petCenterGlobal = coordinates.positionInWindow() +
                            Offset(
                                x = coordinates.size.width / 2f,
                                y = coordinates.size.height / 2f,
                            )
                    }
                    .size(84.dp),
            ) {
                QingPet(
                    pet = pet,
                    mood = displayMood,
                    modifier = Modifier.size(84.dp),
                    lookAngle = effectiveLook,
                    onPetted = onPetted,
                )
            }
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "Lv.$level",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }
    }
}
