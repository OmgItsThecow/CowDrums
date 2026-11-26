package com.cowdrums.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cowdrums.ui.theme.CowColors
import com.cowdrums.viewmodel.DrumMachineUiState

@Composable
fun TransportBar(
    state: DrumMachineUiState,
    onTogglePlay: () -> Unit,
    onBpmDelta: (Int) -> Unit,
    onTapTempo: () -> Unit,
    onToggleMetronome: () -> Unit,
    onOpenKitPicker: () -> Unit
) {
    Surface(
        color = CowColors.SurfacePanel,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Top row: Brand + Active Preset + Kit Picker Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "COWDRUMS",
                        color = CowColors.AccentAmber,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (state.songMode) "SONG MODE" else state.activePreset.name,
                        color = CowColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Kit / SF2 selector pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(CowColors.SurfaceCard)
                        .border(1.dp, CowColors.BorderSubtle, RoundedCornerShape(16.dp))
                        .clickable { onOpenKitPicker() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = "Sound Kit",
                        tint = CowColors.AccentCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (state.isLoadingSound) "Loading..." else state.soundSource.label,
                        color = CowColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Second row: Play/Stop + BPM +/- + TAP + Metronome
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Play / Stop Button
                Button(
                    onClick = onTogglePlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isPlaying) CowColors.AccentCoral else CowColors.AccentGreen,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Stop" else "Play",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (state.isPlaying) "STOP" else "PLAY",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                // BPM Stepper
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CowColors.SurfaceCard)
                        .border(1.dp, CowColors.BorderSubtle, RoundedCornerShape(10.dp))
                ) {
                    IconButton(
                        onClick = { onBpmDelta(-1) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.Remove,
                            contentDescription = "Decrease BPM",
                            tint = CowColors.TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "${state.bpm}",
                            color = CowColors.AccentAmber,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            lineHeight = 15.sp
                        )
                        Text(
                            text = "BPM",
                            color = CowColors.TextMuted,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 9.sp
                        )
                    }
                    IconButton(
                        onClick = { onBpmDelta(1) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Increase BPM",
                            tint = CowColors.TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Tap Tempo Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CowColors.SurfaceCard)
                        .border(1.dp, CowColors.BorderSubtle, RoundedCornerShape(10.dp))
                        .clickable { onTapTempo() }
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = "TAP",
                        color = CowColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.weight(1f))

                // Metronome Toggle
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (state.metronomeEnabled) CowColors.AccentCyan.copy(alpha = 0.22f)
                            else CowColors.SurfaceCard
                        )
                        .border(
                            1.dp,
                            if (state.metronomeEnabled) CowColors.AccentCyan else CowColors.BorderSubtle,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onToggleMetronome() }
                        .padding(horizontal = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Metronome",
                            tint = if (state.metronomeEnabled) CowColors.AccentCyan else CowColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "CLICK",
                            color = if (state.metronomeEnabled) CowColors.AccentCyan else CowColors.TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Beat / Step LED Progress Strip
            val ts = state.activePreset.timeSignature
            val beats = ts.beatsPerBar
            val currentBeat = if (state.currentStep >= 0) {
                (state.currentStep / ts.stepsPerBeat.coerceAtLeast(1)) % beats
            } else -1

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (b in 0 until beats) {
                    val active = b == currentBeat && state.isPlaying
                    val color = when {
                        active && state.fillActive -> CowColors.AccentPink
                        active && b == 0 -> CowColors.AccentAmber
                        active -> CowColors.AccentCyan
                        else -> CowColors.SurfaceElevated
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
        }
    }
}
