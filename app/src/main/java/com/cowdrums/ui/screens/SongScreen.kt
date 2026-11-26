package com.cowdrums.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.cowdrums.model.PatternSlot
import com.cowdrums.model.SongSection
import com.cowdrums.ui.theme.CowColors
import com.cowdrums.viewmodel.DrumMachineUiState

@Composable
fun SongScreen(
    state: DrumMachineUiState,
    onPlaySong: () -> Unit,
    onStopSong: () -> Unit,
    onAddCurrentPreset: () -> Unit,
    onUpdateSection: (String, (SongSection) -> SongSection) -> Unit,
    onRemoveSection: (String) -> Unit,
    onMoveSection: (Int, Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CowColors.BgDark)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Header & Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Song Arrangement",
                    color = CowColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Chain presets with bar repeats & automatic end-of-section fills",
                    color = CowColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isPlayingSong = state.songMode && state.isPlaying
            Button(
                onClick = { if (isPlayingSong) onStopSong() else onPlaySong() },
                enabled = state.songSections.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPlayingSong) CowColors.AccentCoral else CowColors.AccentGreen,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isPlayingSong) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isPlayingSong) "STOP SONG" else "PLAY SONG",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = onAddCurrentPreset,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CowColors.AccentAmber,
                    contentColor = Color(0xFF0D0F14)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "ADD ACTIVE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (state.songSections.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Tap '+' on any Preset or 'ADD ACTIVE' above to build a song.",
                    color = CowColors.TextMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(state.songSections, key = { _, sec -> sec.id }) { idx, sec ->
                    val isActive = state.songMode && state.isPlaying && state.activeSongSectionIndex == idx
                    SongSectionCard(
                        index = idx,
                        total = state.songSections.size,
                        section = sec,
                        isActive = isActive,
                        onCycleSlot = {
                            val nextSlot = when (sec.slot) {
                                PatternSlot.MAIN_A -> PatternSlot.VAR_B
                                PatternSlot.VAR_B -> PatternSlot.FILL
                                PatternSlot.FILL -> PatternSlot.MAIN_A
                            }
                            onUpdateSection(sec.id) { it.copy(slot = nextSlot) }
                        },
                        onBarsDelta = { d ->
                            onUpdateSection(sec.id) { it.copy(bars = (it.bars + d).coerceIn(1, 32)) }
                        },
                        onToggleEndFill = {
                            onUpdateSection(sec.id) { it.copy(playFillOnLastBar = !it.playFillOnLastBar) }
                        },
                        onMoveUp = { onMoveSection(idx, -1) },
                        onMoveDown = { onMoveSection(idx, 1) },
                        onRemove = { onRemoveSection(sec.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SongSectionCard(
    index: Int,
    total: Int,
    section: SongSection,
    isActive: Boolean,
    onCycleSlot: () -> Unit,
    onBarsDelta: (Int) -> Unit,
    onToggleEndFill: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) CowColors.AccentGreen.copy(alpha = 0.14f) else CowColors.SurfaceCard)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) CowColors.AccentGreen else CowColors.BorderSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text(
                    text = "${index + 1}.",
                    color = if (isActive) CowColors.AccentGreen else CowColors.AccentAmber,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = section.presetName,
                    color = CowColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMoveUp, enabled = index > 0, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", tint = CowColors.TextSecondary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onMoveDown, enabled = index < total - 1, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", tint = CowColors.TextSecondary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = CowColors.TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Slot button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CowColors.SurfacePanel)
                    .border(1.dp, CowColors.AccentCyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .clickable { onCycleSlot() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = section.slot.label,
                    color = CowColors.AccentCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Bars stepper
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CowColors.SurfacePanel)
            ) {
                IconButton(onClick = { onBarsDelta(-1) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "Fewer bars", tint = CowColors.TextPrimary, modifier = Modifier.size(14.dp))
                }
                Text(
                    text = "${section.bars} bars",
                    color = CowColors.TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { onBarsDelta(1) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "More bars", tint = CowColors.TextPrimary, modifier = Modifier.size(14.dp))
                }
            }

            // Auto-Fill on last bar toggle
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (section.playFillOnLastBar) CowColors.AccentPink.copy(alpha = 0.22f)
                        else CowColors.SurfacePanel
                    )
                    .border(
                        1.dp,
                        if (section.playFillOnLastBar) CowColors.AccentPink else CowColors.BorderSubtle,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onToggleEndFill() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (section.playFillOnLastBar) "END FILL: ON" else "END FILL: OFF",
                    color = if (section.playFillOnLastBar) CowColors.AccentPink else CowColors.TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
