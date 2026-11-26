package com.cowdrums.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.cowdrums.audio.SoundFont2Parser
import com.cowdrums.model.DrumPreset
import com.cowdrums.model.LoadedSoundFont
import com.cowdrums.model.SongSection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class CowDrumsRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val customPresetsFile: File
        get() = File(context.filesDir, "custom_presets.json")

    private val songFile: File
        get() = File(context.filesDir, "song_sections.json")

    private val soundFontsDir: File
        get() = File(context.filesDir, "soundfonts").apply { mkdirs() }

    fun loadCustomPresets(): List<DrumPreset> {
        if (!customPresetsFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<DrumPreset>>(customPresetsFile.readText())
        }.getOrDefault(emptyList())
    }

    fun saveCustomPresets(presets: List<DrumPreset>) {
        runCatching {
            customPresetsFile.writeText(json.encodeToString(presets))
        }
    }

    fun loadSongSections(): List<SongSection> {
        if (!songFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<SongSection>>(songFile.readText())
        }.getOrDefault(emptyList())
    }

    fun saveSongSections(sections: List<SongSection>) {
        runCatching {
            songFile.writeText(json.encodeToString(sections))
        }
    }

    fun listLoadedSoundFonts(): List<LoadedSoundFont> {
        val files = soundFontsDir.listFiles { f ->
            f.isFile && f.name.lowercase().endsWith(".sf2")
        } ?: return emptyList()

        return files.sortedBy { it.name.lowercase() }.mapNotNull { file ->
            val presets = SoundFont2Parser.listPresets(file)
            if (presets.isEmpty()) null
            else LoadedSoundFont(
                fileName = file.name,
                displayName = file.nameWithoutExtension,
                filePath = file.absolutePath,
                presets = presets
            )
        }
    }

    fun importSoundFont(uri: Uri): Result<LoadedSoundFont> {
        return runCatching {
            val displayName = queryDisplayName(uri) ?: "soundfont_${System.currentTimeMillis()}.sf2"
            val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .let { if (it.lowercase().endsWith(".sf2")) it else "$it.sf2" }

            val target = File(soundFontsDir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("Could not open selected SoundFont file.")

            val presets = SoundFont2Parser.listPresets(target)
            if (presets.isEmpty()) {
                target.delete()
                error("File is not a valid SoundFont 2 (.sf2) bank or contains no presets.")
            }

            LoadedSoundFont(
                fileName = target.name,
                displayName = target.nameWithoutExtension,
                filePath = target.absolutePath,
                presets = presets
            )
        }
    }

    fun deleteSoundFont(fileName: String) {
        val f = File(soundFontsDir, fileName)
        if (f.exists()) f.delete()
    }

    fun soundFontFile(fileName: String): File = File(soundFontsDir, fileName)

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }
}
