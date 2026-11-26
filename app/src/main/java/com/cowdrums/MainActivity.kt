package com.cowdrums

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cowdrums.ui.components.KitSoundFontSheet
import com.cowdrums.ui.components.TransportBar
import com.cowdrums.ui.screens.MixerScreen
import com.cowdrums.ui.screens.PerformScreen
import com.cowdrums.ui.screens.PresetsScreen
import com.cowdrums.ui.screens.SequencerScreen
import com.cowdrums.ui.screens.SongScreen
import com.cowdrums.ui.theme.CowColors
import com.cowdrums.ui.theme.CowDrumsTheme
import com.cowdrums.viewmodel.DrumMachineViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CowDrumsTheme {
                CowDrumsApp()
            }
        }
    }
}

private enum class CowTab(val label: String, val icon: ImageVector) {
    PRESETS("Presets", Icons.Default.LibraryMusic),
    PERFORM("Perform", Icons.Default.TouchApp),
    SEQUENCER("Sequencer", Icons.Default.GridView),
    MIXER("Mixer", Icons.Default.Tune),
    SONG("Song", Icons.AutoMirrored.Filled.QueueMusic)
}

@Composable
fun CowDrumsApp(vm: DrumMachineViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var activeTab by remember { mutableStateOf(CowTab.PRESETS) }
    var showKitSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val sf2PickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importSoundFont(uri)
        }
    }

    val wavExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        if (uri != null) {
            vm.exportActivePatternWav(uri)
        }
    }

    val midiExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/midi")
    ) { uri ->
        if (uri != null) {
            vm.exportActivePatternMidi(uri)
        }
    }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            vm.clearStatusMessage()
        }
    }

    Scaffold(
        containerColor = CowColors.BgDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TransportBar(
                state = state,
                onTogglePlay = vm::togglePlay,
                onBpmDelta = { delta -> vm.setBpm(state.bpm + delta) },
                onTapTempo = vm::tapTempo,
                onToggleMetronome = vm::toggleMetronome,
                onOpenKitPicker = { showKitSheet = true }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CowColors.SurfacePanel
            ) {
                CowTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 10.sp,
                                fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CowColors.AccentAmber,
                            selectedTextColor = CowColors.AccentAmber,
                            indicatorColor = CowColors.AccentAmber.copy(alpha = 0.16f),
                            unselectedIconColor = CowColors.TextSecondary,
                            unselectedTextColor = CowColors.TextSecondary
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTab) {
                CowTab.PRESETS -> PresetsScreen(
                    state = state,
                    onSelectPreset = vm::selectPreset,
                    onTogglePlay = vm::togglePlay,
                    onSelectSlot = vm::selectSlot,
                    onTriggerFill = vm::triggerFill,
                    onToggleAutoFill = vm::toggleAutoFill,
                    onAddToSong = { preset ->
                        vm.addSongSection(preset)
                    },
                    onDeleteCustomPreset = vm::deleteCustomPreset
                )
                CowTab.PERFORM -> PerformScreen(
                    state = state,
                    onTriggerPad = vm::triggerPad,
                    onSelectSlot = vm::selectSlot,
                    onTriggerFill = vm::triggerFill,
                    onToggleAutoFill = vm::toggleAutoFill,
                    onToggleLiveRecord = vm::toggleLiveRecord,
                    onSwingChange = vm::setSwing
                )
                CowTab.SEQUENCER -> SequencerScreen(
                    state = state,
                    onSelectSlot = vm::selectSlot,
                    onCycleStep = vm::cycleStep,
                    onAuditionInstrument = { inst -> vm.triggerPad(inst, 0.9f) },
                    onClearSlot = vm::clearActiveSlot,
                    onToggleBarLength = vm::toggleBarLength,
                    onSetTimeSignature = vm::setTimeSignature,
                    onSaveCustomPreset = vm::saveCurrentAsCustomPreset,
                    onExportWav = {
                        val slug = state.activePreset.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        wavExportLauncher.launch("${slug}_${state.bpm}bpm.wav")
                    },
                    onExportMidi = {
                        val slug = state.activePreset.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        midiExportLauncher.launch("${slug}_${state.bpm}bpm.mid")
                    }
                )
                CowTab.MIXER -> MixerScreen(
                    state = state,
                    onUpdateChannel = vm::updateChannelMix,
                    onAudition = { inst -> vm.triggerPad(inst, 0.9f) },
                    onResetMixer = vm::resetMixer,
                    onOpenKitPicker = { showKitSheet = true }
                )
                CowTab.SONG -> SongScreen(
                    state = state,
                    onPlaySong = vm::playSong,
                    onStopSong = vm::stopPlayback,
                    onAddCurrentPreset = { vm.addSongSection(state.activePreset, state.selectedSlot) },
                    onUpdateSection = vm::updateSongSection,
                    onRemoveSection = vm::removeSongSection,
                    onMoveSection = vm::moveSongSection
                )
            }
        }
    }

    if (showKitSheet) {
        KitSoundFontSheet(
            state = state,
            onDismiss = { showKitSheet = false },
            onSelectBuiltInKit = vm::selectBuiltInKit,
            onImportSoundFontClick = {
                sf2PickerLauncher.launch(arrayOf("*/*"))
            },
            onSelectSoundFontPreset = vm::selectSoundFontPreset,
            onDeleteSoundFont = vm::deleteSoundFont,
            onToggleLockKit = vm::toggleLockKit
        )
    }
}
