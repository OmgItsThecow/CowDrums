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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cowdrums.model.DrumGenre
import com.cowdrums.model.DrumPreset
import com.cowdrums.model.PatternSlot
import com.cowdrums.ui.theme.CowColors
import com.cowdrums.viewmodel.DrumMachineUiState

@Composable
fun PresetsScreen(
    state: DrumMachineUiState,
    onSelectPreset: (DrumPreset, Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onSelectSlot: (PatternSlot) -> Unit,
    onTriggerFill: () -> Unit,
    onToggleAutoFill: () -> Unit,
    onAddToSong: (DrumPreset) -> Unit,
    onDeleteCustomPreset: (DrumPreset) -> Unit
) {
    var selectedGenre by remember { mutableStateOf<DrumGenre?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(state.allPresets, selectedGenre, searchQuery) {
        state.allPresets.filter { preset ->
            val genreOk = selectedGenre == null || preset.genre == selectedGenre
            val queryOk = searchQuery.isBlank() ||
                preset.name.contains(searchQuery, ignoreCase = true) ||
                preset.description.contains(searchQuery, ignoreCase = true) ||
                preset.genre.label.contains(searchQuery, ignoreCase = true)
            genreOk && queryOk
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CowColors.BgDark)
    ) {
        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    "Search ${state.allPresets.size} ready-to-play patterns...",
                    color = CowColors.TextMuted,
                    fontSize = 13.sp
                )
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = CowColors.TextMuted)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = CowColors.TextSecondary)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CowColors.SurfaceCard,
                unfocusedContainerColor = CowColors.SurfaceCard,
                focusedBorderColor = CowColors.AccentAmber,
                unfocusedBorderColor = CowColors.BorderSubtle,
                focusedTextColor = CowColors.TextPrimary,
                unfocusedTextColor = CowColors.TextPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )

        // Genre Filter Chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                GenreChip(
                    label = "All (${state.allPresets.size})",
                    selected = selectedGenre == null,
                    onClick = { selectedGenre = null }
                )
            }
            items(DrumGenre.entries) { genre ->
                val count = state.allPresets.count { it.genre == genre }
                if (count > 0) {
                    GenreChip(
                        label = "${genre.label} ($count)",
                        selected = selectedGenre == genre,
                        onClick = { selectedGenre = if (selectedGenre == genre) null else genre }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Preset List
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filtered, key = { it.id }) { preset ->
                val isCurrent = state.activePreset.id == preset.id && !state.songMode
                PresetCard(
                    preset = preset,
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && state.isPlaying,
                    onTapPlay = {
                        if (isCurrent) {
                            onTogglePlay()
                        } else {
                            onSelectPreset(preset, true)
                        }
                    },
                    onAddToSong = { onAddToSong(preset) },
                    onDelete = if (preset.isCustom) ({ onDeleteCustomPreset(preset) }) else null
                )
            }
        }

        // Bottom Live Groove Performance Bar (MAIN A / VAR B / FILL / AUTO-FILL)
        Surface(
            color = CowColors.SurfacePanel,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
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
                    label = if (state.fillActive) "FILLING!" else "FILL",
                    selected = state.fillActive,
                    accent = CowColors.AccentPink,
                    modifier = Modifier.weight(1f),
                    onClick = onTriggerFill
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (state.autoFillEvery4Bars) CowColors.AccentViolet.copy(alpha = 0.28f)
                            else CowColors.SurfaceCard
                        )
                        .border(
                            1.dp,
                            if (state.autoFillEvery4Bars) CowColors.AccentViolet else CowColors.BorderSubtle,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onToggleAutoFill() }
                        .padding(horizontal = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = "Auto-Fill",
                            tint = if (state.autoFillEvery4Bars) CowColors.AccentViolet else CowColors.TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "4B",
                            color = if (state.autoFillEvery4Bars) CowColors.AccentViolet else CowColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) CowColors.AccentAmber else CowColors.SurfaceCard)
            .border(
                1.dp,
                if (selected) CowColors.AccentAmber else CowColors.BorderSubtle,
                RoundedCornerShape(20.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF111318) else CowColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun PresetCard(
    preset: DrumPreset,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onTapPlay: () -> Unit,
    onAddToSong: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val borderColor = if (isCurrent) CowColors.AccentAmber else CowColors.BorderSubtle
    val bgColor = if (isCurrent) CowColors.AccentAmber.copy(alpha = 0.10f) else CowColors.SurfaceCard

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onTapPlay() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Play / Stop Circle Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        isPlaying -> CowColors.AccentCoral
                        isCurrent -> CowColors.AccentAmber
                        else -> CowColors.SurfaceElevated
                    }
                )
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Stop" else "Play",
                tint = if (isPlaying || isCurrent) Color.White else CowColors.AccentAmber,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preset.name,
                    color = if (isCurrent) CowColors.AccentAmber else CowColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                TagPill(preset.genre.label, CowColors.AccentCyan)
            }

            if (preset.description.isNotBlank()) {
                Text(
                    text = preset.description,
                    color = CowColors.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill("${preset.bpm} BPM", CowColors.TextSecondary)
                TagPill(preset.timeSignature.label, CowColors.TextSecondary)
                if (preset.swing > 0.01f) {
                    TagPill("Swing ${(preset.swing * 100).toInt()}%", CowColors.AccentViolet)
                }
                TagPill(preset.recommendedKit.displayName, CowColors.TextMuted)
            }
        }

        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete preset",
                    tint = CowColors.TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        IconButton(onClick = onAddToSong, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add to Song",
                tint = CowColors.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun TagPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(CowColors.SurfacePanel)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun SlotButton(
    label: String,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent else CowColors.SurfaceCard)
            .border(1.dp, if (selected) accent else CowColors.BorderSubtle, RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF0D0F14) else CowColors.TextPrimary,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 12.sp
        )
    }
}
