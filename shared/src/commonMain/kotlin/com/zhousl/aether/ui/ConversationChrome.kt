package com.zhousl.aether.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherPrimary
import com.zhousl.aether.platform.LocalReduceMotion
import com.zhousl.aether.ui.theme.AetherSurface

private val ConversationControlShadow = Color(0x14000000)
private val ConversationControlHalo = Color(0x18000000)
private val ConversationMotionEasing = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)

// ── Qing glass design system (IB-inspired: floating capsule + liquid glass) ──
enum class AetherAgentStatus { Idle, Working, Connecting }

@Composable
private fun isGlassDarkTheme(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable
fun aetherGlassSurfaceColor(): Color {
    val dark = isGlassDarkTheme()
    return if (dark) Color(0xE61B2230) else Color(0xE8FFFFFF)
}

@Composable
fun aetherGlassBorderColor(): Color {
    val dark = isGlassDarkTheme()
    return if (dark) Color(0x52A5BCE6) else Color(0x668E8E93)
}

@Composable
fun aetherGlassShadowColor(): Color {
    val dark = isGlassDarkTheme()
    return if (dark) Color(0x66000000) else Color(0x33000000)
}

@Composable
fun aetherGlassControlColor(): Color {
    val dark = isGlassDarkTheme()
    return if (dark) Color(0xB8232C3C) else Color(0xB8FFFFFF)
}

@Composable
fun aetherGlassPillColor(): Color {
    val dark = isGlassDarkTheme()
    return if (dark) Color(0xCC2A3344) else Color(0xD9FFFFFF)
}

@Composable
fun AetherGlassCapsule(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    borderAlpha: Float = 1f,
    surfaceAlpha: Float = 1f,
    topHighlight: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val baseSurface = aetherGlassSurfaceColor()
    val surface = baseSurface.copy(alpha = baseSurface.alpha * surfaceAlpha)
    val border = aetherGlassBorderColor()
    val shadow = aetherGlassShadowColor()
    Box(
        modifier = modifier
            .shadow(10.dp, shape, ambientColor = shadow, spotColor = shadow)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(surface, surface.copy(alpha = surface.alpha * 0.80f)),
                ),
            )
            .then(
                if (borderAlpha > 0.01f) {
                    Modifier.border(1.dp, border.copy(alpha = border.alpha * borderAlpha), shape)
                } else {
                    Modifier
                }
            ),
    ) {
        content()
        if (topHighlight) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.70f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
    }
}

data class AetherTopBarGlassStyle(
    val borderAlpha: Float = 1f,
    val surfaceAlpha: Float = 1f,
    val topHighlight: Boolean = false,
    val controlSize: Dp = 38.dp,
    val controlIconSize: Dp = 19.dp,
    val controlBorder: Boolean = true,
    val controlHalo: Boolean = true,
    val controlContainerColor: Color? = null,
)

@Composable
fun AetherGlassStatusDot(
    status: AetherAgentStatus,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition()
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (status == AetherAgentStatus.Connecting) 700 else 1100,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    val dotColor = when (status) {
        AetherAgentStatus.Idle -> AetherOnSurfaceVariant.copy(alpha = 0.55f)
        AetherAgentStatus.Working -> AetherPrimary
        AetherAgentStatus.Connecting -> Color(0xFFE8A33D)
    }
    Box(
        modifier = modifier.size(13.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (status != AetherAgentStatus.Idle && !reduceMotion) {
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = pulseAlpha * 0.30f)),
            )
        }
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    if (status == AetherAgentStatus.Idle || reduceMotion) {
                        dotColor
                    } else {
                        dotColor.copy(alpha = pulseAlpha)
                    },
                ),
        )
    }
}

@Composable
fun AetherConversationTopBarFrame(
    menuDescription: String,
    newChatDescription: String,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    showMenu: Boolean = true,
    modifier: Modifier = Modifier,
    glass: AetherTopBarGlassStyle = AetherTopBarGlassStyle(),
    centerContent: @Composable BoxScope.() -> Unit,
) {
    AetherGlassCapsule(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        cornerRadius = 20.dp,
        borderAlpha = glass.borderAlpha,
        surfaceAlpha = glass.surfaceAlpha,
        topHighlight = glass.topHighlight,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showMenu) {
                HeaderCircleButton(
                    icon = Icons.Rounded.Menu,
                    contentDescription = menuDescription,
                    onClick = onMenu,
                    size = glass.controlSize,
                    iconSize = glass.controlIconSize,
                    containerColor = glass.controlContainerColor ?: aetherGlassControlColor(),
                    borderColor = if (glass.controlBorder) aetherGlassBorderColor() else null,
                    showHalo = glass.controlHalo,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = if (showMenu) 10.dp else 5.dp,
                        end = 10.dp,
                    ),
                content = centerContent,
            )
            HeaderCircleButton(
                icon = LucideIcons.SquarePen,
                contentDescription = newChatDescription,
                onClick = onNewChat,
                size = glass.controlSize,
                iconSize = glass.controlIconSize,
                containerColor = glass.controlContainerColor ?: aetherGlassControlColor(),
                borderColor = if (glass.controlBorder) aetherGlassBorderColor() else null,
                showHalo = glass.controlHalo,
            )
        }
    }
}

@Composable
fun AetherSimpleModelSelector(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().height(38.dp)) {
        Box(
            modifier = Modifier.matchParentSize()
                .offset(y = 4.dp)
                .blur(14.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .clip(RoundedCornerShape(999.dp))
                .background(ConversationControlHalo),
        )
        Row(
            modifier = Modifier.matchParentSize()
                .clip(RoundedCornerShape(999.dp))
                .background(AetherSurface.copy(alpha = 0.96f))
                .padding(horizontal = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal),
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun AetherConversationEmptyState(
    welcomeLabel: String,
    analyzeImageLabel: String,
    codeLabel: String,
    helpWriteLabel: String,
    summarizeFileLabel: String,
    inputFocused: Boolean,
    onStarterPromptSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val titleOffset by animateDpAsState(
        targetValue = if (inputFocused) (-34).dp else (-24).dp,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 260, easing = ConversationMotionEasing),
        label = "empty_state_title_offset",
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 26.dp)
            .offset(y = titleOffset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = welcomeLabel,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
            color = AetherOnSurface,
        )
        Spacer(modifier = Modifier.height(26.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConversationStarterChip(
                    icon = Icons.Rounded.Image,
                    label = analyzeImageLabel,
                    iconTint = Color(0xFF38A961),
                    onClick = {
                        onStarterPromptSelected("Analyze this image and describe the important details.")
                    },
                )
                ConversationStarterChip(
                    icon = Icons.Rounded.Terminal,
                    label = codeLabel,
                    iconTint = Color(0xFF7D70DD),
                    onClick = { onStarterPromptSelected("Help me write or debug this code: ") },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConversationStarterChip(
                    icon = Icons.Rounded.AutoAwesome,
                    label = helpWriteLabel,
                    iconTint = Color(0xFFE48AAE),
                    onClick = {
                        onStarterPromptSelected("Help me write a clear, polished message about ")
                    },
                )
                ConversationStarterChip(
                    icon = Icons.Rounded.AttachFile,
                    label = summarizeFileLabel,
                    iconTint = Color(0xFF66C7D4),
                    onClick = {
                        onStarterPromptSelected("Summarize this file and list the key points.")
                    },
                )
            }
        }
    }
}

@Composable
private fun ConversationStarterChip(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .shadow(
                6.dp,
                RoundedCornerShape(999.dp),
                ambientColor = ConversationControlShadow,
                spotColor = ConversationControlShadow,
            )
            .clip(RoundedCornerShape(999.dp))
            .background(AetherSurface.copy(alpha = 0.98f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = AetherOnSurfaceVariant,
        )
    }
}
