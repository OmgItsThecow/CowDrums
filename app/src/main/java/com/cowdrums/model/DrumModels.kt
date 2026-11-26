package com.cowdrums.model

import kotlinx.serialization.Serializable

/**
 * 12-piece drum kit mapped to General MIDI (GM) Percussion Note Numbers (Channel 10 / Bank 128).
 */
enum class DrumInstrument(
    val shortName: String,
    val displayName: String,
    val gmNote: Int,
    val fallbackGmNotes: List<Int>,
    val chokeGroup: Int, // 0 = none, 1 = hi-hat choke group
    val colorHex: Long
) {
    KICK(
        shortName = "KCK",
        displayName = "Kick",
        gmNote = 36,
        fallbackGmNotes = listOf(35),
        chokeGroup = 0,
        colorHex = 0xFFEF4444
    ),
    SNARE(
        shortName = "SNR",
        displayName = "Snare",
        gmNote = 38,
        fallbackGmNotes = listOf(40),
        chokeGroup = 0,
        colorHex = 0xFFF59E0B
    ),
    RIM(
        shortName = "RIM",
        displayName = "Rimshot",
        gmNote = 37,
        fallbackGmNotes = listOf(38),
        chokeGroup = 0,
        colorHex = 0xFFFBBF24
    ),
    CLAP(
        shortName = "CLP",
        displayName = "Clap",
        gmNote = 39,
        fallbackGmNotes = listOf(38),
        chokeGroup = 0,
        colorHex = 0xFFEC4899
    ),
    CHH(
        shortName = "CHH",
        displayName = "Closed HH",
        gmNote = 42,
        fallbackGmNotes = listOf(44),
        chokeGroup = 1,
        colorHex = 0xFF06B6D4
    ),
    OHH(
        shortName = "OHH",
        displayName = "Open HH",
        gmNote = 46,
        fallbackGmNotes = listOf(42),
        chokeGroup = 1,
        colorHex = 0xFF22D3EE
    ),
    LOW_TOM(
        shortName = "LTOM",
        displayName = "Low Tom",
        gmNote = 45,
        fallbackGmNotes = listOf(41, 43),
        chokeGroup = 0,
        colorHex = 0xFF8B5CF6
    ),
    MID_TOM(
        shortName = "MTOM",
        displayName = "Mid Tom",
        gmNote = 47,
        fallbackGmNotes = listOf(48),
        chokeGroup = 0,
        colorHex = 0xFFA855F7
    ),
    HIGH_TOM(
        shortName = "HTOM",
        displayName = "High Tom",
        gmNote = 50,
        fallbackGmNotes = listOf(48, 47),
        chokeGroup = 0,
        colorHex = 0xFFC084FC
    ),
    CRASH(
        shortName = "CRSH",
        displayName = "Crash",
        gmNote = 49,
        fallbackGmNotes = listOf(57, 55),
        chokeGroup = 0,
        colorHex = 0xFF10B981
    ),
    RIDE(
        shortName = "RIDE",
        displayName = "Ride",
        gmNote = 51,
        fallbackGmNotes = listOf(59, 53),
        chokeGroup = 0,
        colorHex = 0xFF34D399
    ),
    PERC(
        shortName = "PERC",
        displayName = "Cowbell/Perc",
        gmNote = 56,
        fallbackGmNotes = listOf(54, 75, 60, 62),
        chokeGroup = 0,
        colorHex = 0xFF3B82F6
    );

    companion object {
        val ALL = entries.toList()
        fun fromNameOrNull(name: String): DrumInstrument? = entries.firstOrNull { it.name == name }
    }
}

@Serializable
enum class TimeSignature(
    val label: String,
    val stepsPerBar: Int,
    val beatsPerBar: Int,
    val stepsPerBeat: Int
) {
    FOUR_FOUR("4/4", 16, 4, 4),
    THREE_FOUR("3/4", 12, 3, 4),
    SIX_EIGHT("6/8", 12, 6, 2),
    FIVE_FOUR("5/4", 20, 5, 4),
    SEVEN_EIGHT("7/8", 14, 7, 2)
}

@Serializable
enum class PatternSlot(val label: String) {
    MAIN_A("MAIN A"),
    VAR_B("VAR B"),
    FILL("FILL")
}

@Serializable
enum class DrumGenre(val label: String) {
    ROCK("Rock"),
    POP("Pop"),
    FUNK("Funk & Soul"),
    HIP_HOP("Hip-Hop & Trap"),
    EDM("EDM & House"),
    JAZZ("Jazz & Blues"),
    LATIN("Latin & Reggae"),
    METAL("Metal"),
    ODD_TIME("Odd Time"),
    AFRO_RNB("Afro & R&B"),
    CUSTOM("My Presets")
}

@Serializable
enum class BuiltInKit(val displayName: String, val subtitle: String) {
    ACOUSTIC("Standard Acoustic", "Warm studio maple kit"),
    TR_808("TR-808 Classic", "Boomy sub-kick & analog hats"),
    TR_909("TR-909 Club", "Punchy house/techno drums"),
    ROCK_METAL("Rock & Metal", "Attack kick & cracking snare"),
    JAZZ_BRUSH("Jazz & Brush", "Tight bebop & shimmering ride"),
    ELECTRONIC("Modern EDM", "Tight sub & crisp claps"),
    LATIN("Latin Percussion", "Tuned toms, rim & bright clave"),
    LOFI("Lo-Fi Boom-Bap", "Dusty warm bit-crushed kit")
}

data class SoundFontPresetInfo(
    val bank: Int,
    val preset: Int,
    val name: String
)

data class LoadedSoundFont(
    val fileName: String,
    val displayName: String,
    val filePath: String,
    val presets: List<SoundFontPresetInfo>
)

sealed interface SoundSource {
    val label: String

    data class BuiltIn(val kit: BuiltInKit) : SoundSource {
        override val label: String = kit.displayName
    }

    data class SoundFont(
        val fileName: String,
        val displayName: String,
        val bank: Int,
        val preset: Int,
        val presetName: String
    ) : SoundSource {
        override val label: String = "SF2: $presetName"
    }
}

/**
 * Velocity levels per step:
 * 0 = Off
 * 1 = Ghost / Soft (~0.36)
 * 2 = Normal (~0.72)
 * 3 = Accent / Loud (1.0)
 */
object StepVelocity {
    const val OFF = 0
    const val SOFT = 1
    const val MEDIUM = 2
    const val ACCENT = 3

    fun toGain(level: Int): Float = when (level) {
        SOFT -> 0.36f
        MEDIUM -> 0.72f
        ACCENT -> 1.0f
        else -> 0f
    }

    fun next(level: Int): Int = when (level) {
        OFF -> MEDIUM
        MEDIUM -> ACCENT
        ACCENT -> SOFT
        else -> OFF
    }
}

@Serializable
data class StepPattern(
    val stepCount: Int = 16,
    val rows: Map<String, List<Int>> = emptyMap()
) {
    fun velocityAt(instrument: DrumInstrument, step: Int): Int {
        val row = rows[instrument.name] ?: return 0
        return if (step in row.indices) row[step] else 0
    }

    fun withVelocity(instrument: DrumInstrument, step: Int, velocity: Int): StepPattern {
        val current = rows[instrument.name]?.toMutableList() ?: MutableList(stepCount) { 0 }
        while (current.size < stepCount) current.add(0)
        if (step in 0 until stepCount) {
            current[step] = velocity.coerceIn(0, 3)
        }
        val nextRows = rows.toMutableMap()
        nextRows[instrument.name] = current
        return copy(rows = nextRows)
    }

    fun resized(newStepCount: Int): StepPattern {
        if (newStepCount == stepCount) return this
        val nextRows = rows.mapValues { (_, list) ->
            List(newStepCount) { i ->
                if (list.isEmpty()) 0 else list[i % list.size]
            }
        }
        return StepPattern(stepCount = newStepCount, rows = nextRows)
    }

    companion object {
        fun empty(stepCount: Int = 16): StepPattern = StepPattern(stepCount = stepCount, rows = emptyMap())

        /**
         * Compact ASCII pattern builder:
         * '.' = 0 (off)
         * 'g' or '1' = 1 (ghost/soft)
         * 'x' or '2' = 2 (normal)
         * 'X' or '3' = 3 (accent)
         * Spaces and '|' separators are ignored for readability.
         */
        fun fromAscii(stepCount: Int, vararg tracks: Pair<DrumInstrument, String>): StepPattern {
            val map = LinkedHashMap<String, List<Int>>()
            for ((inst, raw) in tracks) {
                val cleaned = raw.filter { it != ' ' && it != '|' }
                val values = List(stepCount) { i ->
                    val ch = if (i < cleaned.length) cleaned[i] else '.'
                    when (ch) {
                        'g', '1' -> StepVelocity.SOFT
                        'x', '2' -> StepVelocity.MEDIUM
                        'X', '3' -> StepVelocity.ACCENT
                        else -> StepVelocity.OFF
                    }
                }
                if (values.any { it > 0 }) {
                    map[inst.name] = values
                }
            }
            return StepPattern(stepCount = stepCount, rows = map)
        }
    }
}

@Serializable
data class DrumPreset(
    val id: String,
    val name: String,
    val genre: DrumGenre,
    val bpm: Int,
    val swing: Float = 0f,
    val timeSignature: TimeSignature = TimeSignature.FOUR_FOUR,
    val recommendedKit: BuiltInKit = BuiltInKit.ACOUSTIC,
    val description: String = "",
    val mainA: StepPattern,
    val varB: StepPattern,
    val fill: StepPattern,
    val isCustom: Boolean = false
) {
    fun patternFor(slot: PatternSlot): StepPattern = when (slot) {
        PatternSlot.MAIN_A -> mainA
        PatternSlot.VAR_B -> varB
        PatternSlot.FILL -> fill
    }

    fun withPattern(slot: PatternSlot, pattern: StepPattern): DrumPreset = when (slot) {
        PatternSlot.MAIN_A -> copy(mainA = pattern)
        PatternSlot.VAR_B -> copy(varB = pattern)
        PatternSlot.FILL -> copy(fill = pattern)
    }
}

@Serializable
data class ChannelMix(
    val volume: Float = 0.85f,
    val pan: Float = 0f, // -1f (left) to +1f (right)
    val pitchSemitones: Float = 0f, // -12f to +12f
    val muted: Boolean = false,
    val solo: Boolean = false
)

@Serializable
data class SongSection(
    val id: String,
    val presetId: String,
    val presetName: String,
    val slot: PatternSlot = PatternSlot.MAIN_A,
    val bars: Int = 4,
    val playFillOnLastBar: Boolean = true
)
