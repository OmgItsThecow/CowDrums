package com.cowdrums.audio

import com.cowdrums.model.BuiltInKit
import com.cowdrums.model.DrumInstrument
import com.cowdrums.model.SoundFontPresetInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.pow

/**
 * Pure-Kotlin SoundFont 2 (.sf2) RIFF binary parser.
 * Parses `sfbk` -> `sdta/smpl` (16-bit PCM) and `pdta/{phdr,pbag,pgen,inst,ibag,igen,shdr}`
 * so users can load any standard GM/Drum .sf2 SoundFont directly on Android without native NDK builds.
 */
object SoundFont2Parser {

    private const val GEN_KEY_RANGE = 43
    private const val GEN_INSTRUMENT = 41
    private const val GEN_SAMPLE_ID = 53
    private const val GEN_OVERRIDING_ROOT_KEY = 58
    private const val GEN_START_ADDR_OFFSET = 0
    private const val GEN_END_ADDR_OFFSET = 1
    private const val GEN_START_ADDR_COARSE_OFFSET = 4
    private const val GEN_END_ADDR_COARSE_OFFSET = 12

    private data class Phdr(
        val name: String,
        val preset: Int,
        val bank: Int,
        val bagNdx: Int
    )

    private data class Bag(val genNdx: Int)

    private data class Gen(val oper: Int, val amount: Short)

    private data class Inst(val name: String, val bagNdx: Int)

    private data class Shdr(
        val name: String,
        val start: Int,
        val end: Int,
        val sampleRate: Int,
        val originalPitch: Int,
        val pitchCorrection: Int,
        val sampleType: Int
    )

    private data class ParsedSf2(
        val smplOffset: Int,
        val smplLength: Int,
        val phdrs: List<Phdr>,
        val pbags: List<Bag>,
        val pgens: List<Gen>,
        val insts: List<Inst>,
        val ibags: List<Bag>,
        val igens: List<Gen>,
        val shdrs: List<Shdr>,
        val bytes: ByteArray
    )

    fun listPresets(file: File): List<SoundFontPresetInfo> {
        val parsed = parse(file) ?: return emptyList()
        val list = parsed.phdrs.dropLast(1).map {
            SoundFontPresetInfo(
                bank = it.bank,
                preset = it.preset,
                name = it.name.ifBlank { "Bank ${it.bank} Preset ${it.preset}" }
            )
        }
        // Sort Bank 128 (GM Percussion) first, then by bank/preset number
        return list.sortedWith(
            compareByDescending<SoundFontPresetInfo> { it.bank == 128 }
                .thenBy { it.bank }
                .thenBy { it.preset }
        )
    }

    /**
     * Extracts 44.1 kHz mono FloatArray drum samples for our 12 DrumInstruments from the chosen SF2 preset.
     * Any instrument note missing in the SF2 falls back to the Acoustic built-in synthesizer so the kit is always playable.
     */
    fun loadKitSamples(
        file: File,
        bank: Int,
        preset: Int,
        targetSampleRate: Int = BuiltInKitSynthesizer.SAMPLE_RATE
    ): Map<DrumInstrument, FloatArray> {
        val fallback = BuiltInKitSynthesizer.renderKit(BuiltInKit.ACOUSTIC)
        val parsed = parse(file) ?: return fallback

        val phdrIndex = parsed.phdrs.dropLast(1).indexOfFirst { it.bank == bank && it.preset == preset }
        if (phdrIndex < 0) return fallback

        // Map MIDI key (0..127) -> best Shdr + overrides
        val keyToZone = HashMap<Int, SampleZone>()
        val pStartBag = parsed.phdrs[phdrIndex].bagNdx
        val pEndBag = parsed.phdrs[phdrIndex + 1].bagNdx

        var globalPresetKeyLo = 0
        var globalPresetKeyHi = 127

        for (b in pStartBag until pEndBag) {
            if (b + 1 >= parsed.pbags.size) break
            val gStart = parsed.pbags[b].genNdx
            val gEnd = parsed.pbags[b + 1].genNdx
            var keyLo = globalPresetKeyLo
            var keyHi = globalPresetKeyHi
            var instIdx = -1

            for (g in gStart until gEnd) {
                if (g >= parsed.pgens.size) break
                val gen = parsed.pgens[g]
                when (gen.oper) {
                    GEN_KEY_RANGE -> {
                        val u = gen.amount.toInt() and 0xFFFF
                        keyLo = u and 0xFF
                        keyHi = (u shr 8) and 0xFF
                    }
                    GEN_INSTRUMENT -> {
                        instIdx = gen.amount.toInt() and 0xFFFF
                    }
                }
            }
            if (instIdx < 0 && b == pStartBag) {
                globalPresetKeyLo = keyLo
                globalPresetKeyHi = keyHi
                continue
            }
            if (instIdx in 0 until (parsed.insts.size - 1)) {
                collectInstrumentZones(parsed, instIdx, keyLo, keyHi, keyToZone)
            }
        }

        val result = LinkedHashMap<DrumInstrument, FloatArray>(DrumInstrument.ALL.size)
        for (inst in DrumInstrument.ALL) {
            val candidateNotes = listOf(inst.gmNote) + inst.fallbackGmNotes
            var loaded: FloatArray? = null
            for (note in candidateNotes) {
                val zone = keyToZone[note]
                if (zone != null) {
                    val pcm = extractSample(parsed, zone, note, targetSampleRate)
                    if (pcm != null && pcm.isNotEmpty()) {
                        loaded = pcm
                        break
                    }
                }
            }
            result[inst] = loaded ?: fallback[inst]!!
        }
        return result
    }

    private data class SampleZone(
        val shdr: Shdr,
        val rootKeyOverride: Int?,
        val startOffset: Int,
        val endOffset: Int
    )

    private fun collectInstrumentZones(
        parsed: ParsedSf2,
        instIdx: Int,
        presetKeyLo: Int,
        presetKeyHi: Int,
        out: MutableMap<Int, SampleZone>
    ) {
        val iStartBag = parsed.insts[instIdx].bagNdx
        val iEndBag = parsed.insts[instIdx + 1].bagNdx

        var globalKeyLo = 0
        var globalKeyHi = 127
        var globalRoot: Int? = null

        for (b in iStartBag until iEndBag) {
            if (b + 1 >= parsed.ibags.size) break
            val gStart = parsed.ibags[b].genNdx
            val gEnd = parsed.ibags[b + 1].genNdx

            var keyLo = globalKeyLo
            var keyHi = globalKeyHi
            var rootOverride = globalRoot
            var sampleId = -1
            var startOff = 0
            var endOff = 0

            for (g in gStart until gEnd) {
                if (g >= parsed.igens.size) break
                val gen = parsed.igens[g]
                when (gen.oper) {
                    GEN_KEY_RANGE -> {
                        val u = gen.amount.toInt() and 0xFFFF
                        keyLo = u and 0xFF
                        keyHi = (u shr 8) and 0xFF
                    }
                    GEN_OVERRIDING_ROOT_KEY -> {
                        val r = gen.amount.toInt()
                        if (r in 0..127) rootOverride = r
                    }
                    GEN_SAMPLE_ID -> {
                        sampleId = gen.amount.toInt() and 0xFFFF
                    }
                    GEN_START_ADDR_OFFSET -> startOff += gen.amount.toInt()
                    GEN_START_ADDR_COARSE_OFFSET -> startOff += gen.amount.toInt() * 32768
                    GEN_END_ADDR_OFFSET -> endOff += gen.amount.toInt()
                    GEN_END_ADDR_COARSE_OFFSET -> endOff += gen.amount.toInt() * 32768
                }
            }

            if (sampleId < 0 && b == iStartBag) {
                globalKeyLo = keyLo
                globalKeyHi = keyHi
                globalRoot = rootOverride
                continue
            }

            if (sampleId in 0 until (parsed.shdrs.size - 1)) {
                val shdr = parsed.shdrs[sampleId]
                // Skip right-channel linked stereo duplicate if left is already mapped
                val lo = maxOf(keyLo, presetKeyLo)
                val hi = minOf(keyHi, presetKeyHi)
                val zone = SampleZone(shdr, rootOverride, startOff, endOff)
                for (k in lo..hi) {
                    if (!out.containsKey(k)) {
                        out[k] = zone
                    }
                }
            }
        }
    }

    private fun extractSample(
        parsed: ParsedSf2,
        zone: SampleZone,
        targetMidiNote: Int,
        targetSampleRate: Int
    ): FloatArray? {
        val shdr = zone.shdr
        val totalSamples = parsed.smplLength / 2
        val start = (shdr.start + zone.startOffset).coerceIn(0, totalSamples)
        val end = (shdr.end + zone.endOffset).coerceIn(start, totalSamples)
        val srcLen = end - start
        if (srcLen <= 16) return null

        // Cap very long samples to 2.5 seconds for crisp drum playback & low memory
        val maxSrcLen = min(srcLen, (shdr.sampleRate.coerceAtLeast(8000) * 2.5).toInt())
        val rootKey = zone.rootKeyOverride ?: if (shdr.originalPitch in 0..127) shdr.originalPitch else targetMidiNote
        val semitoneShift = (targetMidiNote - rootKey) + (shdr.pitchCorrection / 100.0)
        val pitchRatio = 2.0.pow(semitoneShift / 12.0)
        val srcRate = shdr.sampleRate.coerceAtLeast(8000).toDouble()
        val step = (srcRate / targetSampleRate.toDouble()) * pitchRatio

        val outLen = (maxSrcLen / step).toInt().coerceIn(1, targetSampleRate * 3)
        val out = FloatArray(outLen)
        val bytes = parsed.bytes
        val baseByte = parsed.smplOffset + start * 2

        var pos = 0.0
        for (i in 0 until outLen) {
            val idx = pos.toInt()
            if (idx + 1 >= maxSrcLen) break
            val frac = (pos - idx).toFloat()
            val b0 = baseByte + idx * 2
            val s0 = ((bytes[b0].toInt() and 0xFF) or (bytes[b0 + 1].toInt() shl 8)).toShort() / 32768f
            val s1 = ((bytes[b0 + 2].toInt() and 0xFF) or (bytes[b0 + 3].toInt() shl 8)).toShort() / 32768f
            out[i] = s0 + (s1 - s0) * frac
            pos += step
        }
        return out
    }

    private fun parse(file: File): ParsedSf2? {
        if (!file.exists() || !file.canRead()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size < 12) return null

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (readTag(bytes, 0) != "RIFF" || readTag(bytes, 8) != "sfbk") return null

        var smplOffset = -1
        var smplLength = 0
        var phdrs = emptyList<Phdr>()
        var pbags = emptyList<Bag>()
        var pgens = emptyList<Gen>()
        var insts = emptyList<Inst>()
        var ibags = emptyList<Bag>()
        var igens = emptyList<Gen>()
        var shdrs = emptyList<Shdr>()

        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = readTag(bytes, offset)
            val chunkSize = buf.getInt(offset + 4)
            val dataStart = offset + 8
            if (chunkSize < 0 || dataStart + chunkSize > bytes.size) break

            if (chunkId == "LIST" && chunkSize >= 4) {
                val listType = readTag(bytes, dataStart)
                var sub = dataStart + 4
                val listEnd = dataStart + chunkSize
                while (sub + 8 <= listEnd) {
                    val subId = readTag(bytes, sub)
                    val subSize = buf.getInt(sub + 4)
                    val subData = sub + 8
                    if (subSize < 0 || subData + subSize > listEnd) break

                    when {
                        listType == "sdta" && subId == "smpl" -> {
                            smplOffset = subData
                            smplLength = subSize
                        }
                        listType == "pdta" -> when (subId) {
                            "phdr" -> phdrs = parsePhdr(bytes, buf, subData, subSize)
                            "pbag" -> pbags = parseBag(buf, subData, subSize)
                            "pgen" -> pgens = parseGen(buf, subData, subSize)
                            "inst" -> insts = parseInst(bytes, buf, subData, subSize)
                            "ibag" -> ibags = parseBag(buf, subData, subSize)
                            "igen" -> igens = parseGen(buf, subData, subSize)
                            "shdr" -> shdrs = parseShdr(bytes, buf, subData, subSize)
                        }
                    }
                    sub = subData + subSize + (subSize and 1)
                }
            }
            offset = dataStart + chunkSize + (chunkSize and 1)
        }

        if (smplOffset < 0 || phdrs.size < 2 || shdrs.size < 2) return null
        return ParsedSf2(
            smplOffset = smplOffset,
            smplLength = smplLength,
            phdrs = phdrs,
            pbags = pbags,
            pgens = pgens,
            insts = insts,
            ibags = ibags,
            igens = igens,
            shdrs = shdrs,
            bytes = bytes
        )
    }

    private fun readTag(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun readCString(bytes: ByteArray, offset: Int, maxLen: Int): String {
        var len = 0
        while (len < maxLen && offset + len < bytes.size && bytes[offset + len] != 0.toByte()) {
            len++
        }
        return String(bytes, offset, len, Charsets.US_ASCII).trim()
    }

    private fun parsePhdr(bytes: ByteArray, buf: ByteBuffer, start: Int, size: Int): List<Phdr> {
        val count = size / 38
        return List(count) { i ->
            val o = start + i * 38
            Phdr(
                name = readCString(bytes, o, 20),
                preset = buf.getShort(o + 20).toInt() and 0xFFFF,
                bank = buf.getShort(o + 22).toInt() and 0xFFFF,
                bagNdx = buf.getShort(o + 24).toInt() and 0xFFFF
            )
        }
    }

    private fun parseBag(buf: ByteBuffer, start: Int, size: Int): List<Bag> {
        val count = size / 4
        return List(count) { i ->
            val o = start + i * 4
            Bag(genNdx = buf.getShort(o).toInt() and 0xFFFF)
        }
    }

    private fun parseGen(buf: ByteBuffer, start: Int, size: Int): List<Gen> {
        val count = size / 4
        return List(count) { i ->
            val o = start + i * 4
            Gen(
                oper = buf.getShort(o).toInt() and 0xFFFF,
                amount = buf.getShort(o + 2)
            )
        }
    }

    private fun parseInst(bytes: ByteArray, buf: ByteBuffer, start: Int, size: Int): List<Inst> {
        val count = size / 22
        return List(count) { i ->
            val o = start + i * 22
            Inst(
                name = readCString(bytes, o, 20),
                bagNdx = buf.getShort(o + 20).toInt() and 0xFFFF
            )
        }
    }

    private fun parseShdr(bytes: ByteArray, buf: ByteBuffer, start: Int, size: Int): List<Shdr> {
        val count = size / 46
        return List(count) { i ->
            val o = start + i * 46
            Shdr(
                name = readCString(bytes, o, 20),
                start = buf.getInt(o + 20),
                end = buf.getInt(o + 24),
                sampleRate = buf.getInt(o + 36),
                originalPitch = bytes[o + 40].toInt() and 0xFF,
                pitchCorrection = bytes[o + 41].toInt(),
                sampleType = buf.getShort(o + 44).toInt() and 0xFFFF
            )
        }
    }
}
