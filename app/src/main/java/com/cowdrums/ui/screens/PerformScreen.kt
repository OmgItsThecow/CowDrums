package com.cowdrums.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cowdrums.model.DrumInstrument
import com.cowdrums.model.PatternSlot
import com.cowdrums.ui.theme.CowColors
import com.cowdrums.viewmodel.DrumMachineUiState
import kotlinx.coroutines.delay

@Composable
fun PerformScreen(
    state: DrumMachineUiState,
    onTriggerPad: (DrumInstrument, Float) -> Unit,
    onSelectSlot: (PatternSlot) -> Unit,
    onTriggerFill: () -> Unit,
    onToggleAutoFill: () -> Unit,
    onToggleLiveRecord: () -> Unit,
    onSwingChange: (Float) -> Unit
) {
    val flashMap = remember { mutableStateMapOf<DrumInstrument, Long>() }

    // Clear pad hit flashes automatically
    LaunchedEffect(flashMap.size) {
        if (flashMap.isNotEmpty()) {
            delay(110)
            val now = System.currentTimeMillis()
            val expired = flashMap.filterValues { now - it > 95 }.keys.toList()
            expired.forEach { flashMap.remove(it) }
        }
    }

    val activePattern = state.activePreset.patternFor(state.playingSlot)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CowColors.BgDark)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Live Performance Controls: MAIN A / VAR B / FILL / REC
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SlotButton(
                label = "MAIN A",
                selected = state.playingSlot == PatternSlot.MAIN_A && !state.fillActive,
                accent = CowColors.AccentAmber,
                modifier = Modifier.weight(1f),
                onClick = { onSelectSlot(PatternSlot.MAIN_A) }
            )
            SlotButton(
                label = "VAR B",
                selected = state.playingSlot == PatternSlot.VAR_B && !state.fillActive,
                accent = CowColors.AccentCyan,
                modifier = Modifier.weight(1f),
                onClick = { onSelectSlot(PatternSlot.VAR_B) }
            )
            SlotButton(
                label = if (state.fillActive) "FILLING" else "FILL!",
                selected = state.fillActive,
                accent = CowColors.AccentPink,
                modifier = Modifier.weight(1f),
                onClick = onTriggerFill
            )
            // Live Record Overdub Toggle
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (state.liveRecordEnabled) CowColors.AccentCoral.copy(alpha = 0.25f)
                        else CowColors.SurfaceCard
                    )
                    .border(
                        1.dp,
                        if (state.liveRecordEnabled) CowColors.AccentCoral else CowColors.BorderSubtle,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onToggleLiveRecord() }
                    .padding(horizontal = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.FiberManualRecord,
                        contentDescription = "Record",
                        tint = if (state.liveRecordEnabled) CowColors.AccentCoral else CowColors.TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "REC",
                        color = if (state.liveRecordEnabled) CowColors.AccentCoral else CowColors.TextSecondary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 12 Velocity-Sensitive Drum Pads (3 columns x 4 rows)
        val padOrder = listOf(
            DrumInstrument.CRASH, DrumInstrument.RIDE, DrumInstrument.PERC,
            DrumInstrument.HIGH_TOM, DrumInstrument.MID_TOM, DrumInstrument.LOW_TOM,
            DrumInstrument.CHH, DrumInstrument.OHH, DrumInstrument.CLAP,
            DrumInstrument.KICK, DrumInstrument.SNARE, DrumInstrument.RIM
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            padOrder.chunked(3).forEach { rowPads ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowPads.forEach { inst ->
                        val seqHit = state.isPlaying &&
                            state.currentStep >= 0 &&
                            activePattern.velocityAt(inst, state.currentStep) > 0
                        val isLit = seqHit || flashMap.containsKey(inst)
                        DrumPadTile(
                            instrument = inst,
                            isLit = isLit,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                            onHit = { velocity ->
                                flashMap[inst] = System.currentTimeMillis()
                                onTriggerPad(inst, velocity)
                            }
                        )
                    }
                }
            }
        }

        // Swing & Auto-Fill Footer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CowColors.SurfacePanel)
                .border(1.dp, CowColors.BorderSubtle, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SWING ${(state.swing * 100).toInt()}%",
                color = CowColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(74.dp)
            )
            Slider(
                value = state.swing,
                onValueChange = onSwingChange,
                valueRange = 0f..0.70f,
                colors = SliderDefaults.colors(
                    thumbColor = CowColors.AccentAmber,
                    activeTrackColor = CowColors.AccentAmber,
                    inactiveTrackColor = CowColors.SurfaceElevated
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (state.autoFillEvery4Bars) CowColors.AccentViolet.copy(alpha = 0.28f)
                        else CowColors.SurfaceCard
                    )
                    .border(
                        1.dp,
                        if (state.autoFillEvery4Bars) CowColors.AccentViolet else CowColors.BorderSubtle,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onToggleAutoFill() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "AUTO-FILL",
                    color = if (state.autoFillEvery4Bars) CowColors.AccentViolet else CowColors.TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun DrumPadTile(
    instrument: DrumInstrument,
    isLit: Boolean,
    modifier: Modifier = Modifier,
    onHit: (velocity: Float) -> Unit
) {
    val baseColor = Color(instrument.colorHex)
    val bgBrush = if (isLit) {
        Brush.verticalGradient(
            listOf(baseColor.copy(alpha = 0.72f), baseColor.copy(alpha = 0.42f))
        )
    } else {
        Brush.verticalGradient(
            listOf(CowColors.SurfaceElevated, CowColors.SurfaceCard)
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgBrush)
            .border(
                width = if (isLit) 2.dp else 1.dp,
                color = if (isLit) baseColor else baseColor.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp)
            )
            .pointerInput(instrument) {
                detectTapGestures(
                    onPress = { offset ->
                        // Bottom of pad = accent (1.0), top of pad = soft (0.45)
                        val relY = (offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val vel = 0.45f + relY * 0.55f
                        onHit(vel)
                    }
                )
            }
            .padding(10.dp)
    ) {
        // LED indicator dot in top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isLit) Color.White else baseColor.copy(alpha = 0.65f))
        )

        Column(
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            Text(
                text = instrument.shortName,
                color = if (isLit) Color.White else baseColor,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp
            )
            Text(
                text = instrument.displayName,
                color = if (isLit) Color.White.copy(alpha = 0.9f) else CowColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
