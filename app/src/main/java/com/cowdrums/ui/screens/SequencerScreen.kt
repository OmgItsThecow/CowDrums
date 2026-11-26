package com.cowdrums.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cowdrums.model.DrumInstrument
import com.cowdrums.model.PatternSlot
import com.cowdrums.model.StepVelocity
import com.cowdrums.model.TimeSignature
import com.cowdrums.ui.theme.CowColors
import com.cowdrums.viewmodel.DrumMachineUiState

@Composable
fun SequencerScreen(
    state: DrumMachineUiState,
    onSelectSlot: (PatternSlot) -> Unit,
    onCycleStep: (DrumInstrument, Int) -> Unit,
    onAuditionInstrument: (DrumInstrument) -> Unit,
    onClearSlot: () -> Unit,
    onToggleBarLength: () -> Unit,
    onSetTimeSignature: (TimeSignature) -> Unit,
    onSaveCustomPreset: (String) -> Unit,
    onExportWav: () -> Unit,
    onExportMidi: () -> Unit
) {
    var showTimeSigMenu by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("${state.activePreset.name} Edit") }

    val pattern = state.activePreset.patternFor(state.selectedSlot)
    val ts = state.activePreset.timeSignature
    val bars = (pattern.stepCount / ts.stepsPerBar).coerceAtLeast(1)
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CowColors.BgDark)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // Top Toolbar: Slot Selector + TimeSig + Bars + Clear + Save
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlotPill("A", state.selectedSlot == PatternSlot.MAIN_A, CowColors.AccentAmber) {
                onSelectSlot(PatternSlot.MAIN_A)
            }
            SlotPill("B", state.selectedSlot == PatternSlot.VAR_B, CowColors.AccentCyan) {
                onSelectSlot(PatternSlot.VAR_B)
            }
            SlotPill("FILL", state.selectedSlot == PatternSlot.FILL, CowColors.AccentPink) {
                onSelectSlot(PatternSlot.FILL)
            }

            Box {
                SmallActionChip(label = ts.label, onClick = { showTimeSigMenu = true })
                DropdownMenu(
                    expanded = showTimeSigMenu,
                    onDismissRequest = { showTimeSigMenu = false }
                ) {
                    TimeSignature.entries.forEach { sig ->
                        DropdownMenuItem(
                            text = { Text("${sig.label} (${sig.stepsPerBar} steps)") },
                            onClick = {
                                showTimeSigMenu = false
                                onSetTimeSignature(sig)
                            }
                        )
                    }
                }
            }

            SmallActionChip(
                label = "${bars}B (${pattern.stepCount})",
                onClick = onToggleBarLength
            )

            Spacer(Modifier.weight(1f))

            // Clear slot
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CowColors.SurfaceCard)
                    .border(1.dp, CowColors.BorderSubtle, RoundedCornerShape(8.dp))
                    .clickable { onClearSlot() }
            ) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = "Clear pattern",
                    tint = CowColors.TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Save Preset
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CowColors.AccentAmber.copy(alpha = 0.2f))
                    .border(1.dp, CowColors.AccentAmber, RoundedCornerShape(8.dp))
                    .clickable {
                        saveName = "${state.activePreset.name} Custom"
                        showSaveDialog = true
                    }
            ) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = "Save Preset",
                    tint = CowColors.AccentAmber,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Legend + WAV/MIDI Export buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tap cell: Med -> Accent -> Ghost -> Off",
                color = CowColors.TextMuted,
                fontSize = 10.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallActionChip(label = "WAV", onClick = onExportWav)
                SmallActionChip(label = "MIDI", onClick = onExportMidi)
            }
        }

        Spacer(Modifier.height(6.dp))

        // Step Numbers Header + Scrollable 12-row Grid
        Row(modifier = Modifier.fillMaxWidth()) {
            // Fixed Left Instrument Label Column Header
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .width(56.dp)
                    .height(22.dp)
            ) {
                Text("DRUM", color = CowColors.TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }

            // Scrollable Step Numbers
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(hScroll),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (step in 0 until pattern.stepCount) {
                    val isBeatStart = step % ts.stepsPerBeat == 0
                    val isPlayhead = state.isPlaying && state.currentStep == step
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(34.dp)
                            .height(22.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                when {
                                    isPlayhead -> CowColors.AccentAmber
                                    isBeatStart -> CowColors.SurfaceElevated
                                    else -> Color.Transparent
                                }
                            )
                    ) {
                        Text(
                            text = "${step + 1}",
                            color = when {
                                isPlayhead -> Color(0xFF0D0F14)
                                isBeatStart -> CowColors.TextPrimary
                                else -> CowColors.TextMuted
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // 12 Instrument Rows
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(vScroll),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            DrumInstrument.ALL.forEach { inst ->
                val instColor = Color(inst.colorHex)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Instrument Audition Button
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .width(56.dp)
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(CowColors.SurfaceCard)
                            .border(1.dp, instColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .clickable { onAuditionInstrument(inst) }
                            .padding(horizontal = 6.dp)
                    ) {
                        Text(
                            text = inst.shortName,
                            color = instColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    // Synchronized Horizontal Step Cells
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(hScroll),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (step in 0 until pattern.stepCount) {
                            val vel = pattern.velocityAt(inst, step)
                            val isBeatGroupOdd = ((step / ts.stepsPerBeat) % 2) == 1
                            val isPlayhead = state.isPlaying && state.currentStep == step

                            val cellBg = when (vel) {
                                StepVelocity.ACCENT -> instColor
                                StepVelocity.MEDIUM -> instColor.copy(alpha = 0.62f)
                                StepVelocity.SOFT -> instColor.copy(alpha = 0.28f)
                                else -> if (isBeatGroupOdd) CowColors.SurfacePanel else CowColors.SurfaceCard
                            }

                            val borderColor = when {
                                isPlayhead -> Color.White
                                vel > 0 -> instColor
                                else -> CowColors.BorderSubtle.copy(alpha = 0.6f)
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .width(34.dp)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(cellBg)
                                    .border(
                                        width = if (isPlayhead) 1.5.dp else 1.dp,
                                        color = borderColor,
                                        shape = RoundedCornerShape(7.dp)
                                    )
                                    .clickable { onCycleStep(inst, step) }
                            ) {
                                if (vel > 0) {
                                    Text(
                                        text = when (vel) {
                                            StepVelocity.ACCENT -> "●"
                                            StepVelocity.MEDIUM -> "◐"
                                            else -> "○"
                                        },
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = CowColors.SurfacePanel,
            title = { Text("Save Custom Preset", color = CowColors.TextPrimary) },
            text = {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    label = { Text("Preset Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveCustomPreset(saveName)
                        showSaveDialog = false
                    }
                ) {
                    Text("SAVE", color = CowColors.AccentAmber, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("CANCEL", color = CowColors.TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun SlotPill(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent else CowColors.SurfaceCard)
            .border(1.dp, if (selected) accent else CowColors.BorderSubtle, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF0D0F14) else CowColors.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun SmallActionChip(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CowColors.SurfaceCard)
            .border(1.dp, CowColors.BorderSubtle, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp)
    ) {
        Text(
            text = label,
            color = CowColors.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
