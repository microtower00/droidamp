package com.droidamp.player.interop

import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.WinampEqSpec
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryEqfCodecTest {
  private val codec = BinaryEqfCodec()

  @Test
  fun encodeSingleEqf_producesExact299ByteWinampLayout() {
    val preset =
      EqPreset(
        name = "ExtremeTest",
        preampDb = 12.0f,
        bandsDb = listOf(12f, 0f, -12f, 12f, 0f, -12f, 12f, 0f, -12f, 0f),
      )

    val bytes = codec.encodeSingleEqf(preset)
    assertEquals(299, bytes.size)

    val expectedHeader =
      "Winamp EQ library file v1.1".toByteArray(Charsets.US_ASCII) +
        byteArrayOf(0x1A, '!'.code.toByte(), '-'.code.toByte(), '-'.code.toByte())
    assertArrayEquals(expectedHeader, bytes.copyOfRange(0, 31))

    val nameBytes = "ExtremeTest".toByteArray(Charsets.UTF_8)
    assertArrayEquals(nameBytes, bytes.copyOfRange(31, 31 + nameBytes.size))
    for (i in (31 + nameBytes.size) until 288) {
      assertEquals("Expected null padding at byte $i", 0.toByte(), bytes[i])
    }

    assertEquals(0, bytes[288].toInt() and 0xFF) // +12 dB -> 0
    assertEquals(31, bytes[289].toInt() and 0xFF) // 0 dB -> 31
    assertEquals(63, bytes[290].toInt() and 0xFF) // -12 dB -> 63
    assertEquals(0, bytes[298].toInt() and 0xFF) // preamp +12 dB -> 0
  }

  @Test
  fun singleEqf_roundTripsFlatExactZeroAndAllClassicPresetsWithinTolerance() {
    val flatDecoded = codec.parseSingleEqf(codec.encodeSingleEqf(ClassicWinampPresets.FLAT))
    assertEquals("FLAT", flatDecoded.name)
    assertEquals(0.0f, flatDecoded.preampDb, 0.0f)
    flatDecoded.bandsDb.forEach { band -> assertEquals(0.0f, band, 0.0f) }

    for (preset in ClassicWinampPresets.ALL) {
      val bytes = codec.encodeSingleEqf(preset)
      val decoded = codec.parseSingleEqf(bytes)
      assertEquals(preset.name, decoded.name)
      assertTrue(
        "Preamp delta exceeded 0.20 dB for ${preset.name}: ${decoded.preampDb} vs ${preset.preampDb}",
        abs(decoded.preampDb - preset.preampDb) <= 0.20f,
      )
      preset.bandsDb.forEachIndexed { idx, expectedDb ->
        assertTrue(
          "Band $idx delta exceeded 0.20 dB for ${preset.name}: ${decoded.bandsDb[idx]} vs $expectedDb",
          abs(decoded.bandsDb[idx] - expectedDb) <= 0.20f,
        )
      }
    }
  }

  @Test
  fun singleEqf_allBytes0To63RoundTripByteIdentically() {
    for (rawByte in 0..63) {
      val fixture = buildEqfRecordBytes("Byte_$rawByte", List(10) { rawByte }, rawByte)
      val parsed = codec.parseSingleEqf(fixture)
      val reencoded = codec.encodeSingleEqf(parsed)
      assertArrayEquals("Byte $rawByte failed exact round-trip", fixture, reencoded)
    }
  }

  @Test
  fun singleEqf_truncatesLongUtf8NameSafelyWithNullTerminator() {
    val longName = "É".repeat(160) // 320 UTF-8 bytes
    val encoded = codec.encodeSingleEqf(EqPreset(longName, 0f, List(10) { 0f }))
    assertEquals(299, encoded.size)
    assertEquals(0.toByte(), encoded[287]) // Byte 256 of 257-byte name field must be NUL

    val decoded = codec.parseSingleEqf(encoded)
    assertTrue(decoded.name.isNotEmpty())
    assertTrue(decoded.name.toByteArray(Charsets.UTF_8).size <= 256)
    assertTrue(longName.startsWith(decoded.name))
  }

  @Test(expected = IllegalArgumentException::class)
  fun parseSingleEqf_rejectsTruncatedBytes() {
    codec.parseSingleEqf(ByteArray(298))
  }

  @Test(expected = IllegalArgumentException::class)
  fun parseSingleEqf_rejectsOversizedBytes() {
    codec.parseSingleEqf(ByteArray(300))
  }

  @Test(expected = IllegalArgumentException::class)
  fun parseSingleEqf_rejectsCorruptedHeader() {
    val bytes = codec.encodeSingleEqf(ClassicWinampPresets.ROCK)
    bytes[5] = 'X'.code.toByte()
    codec.parseSingleEqf(bytes)
  }

  @Test(expected = IllegalArgumentException::class)
  fun parseSingleEqf_rejectsOutOfRangeBandByte64() {
    val bytes = codec.encodeSingleEqf(ClassicWinampPresets.ROCK)
    bytes[290] = 64.toByte()
    codec.parseSingleEqf(bytes)
  }

  @Test(expected = IllegalArgumentException::class)
  fun parseSingleEqf_rejectsOutOfRangePreampByte255() {
    val bytes = codec.encodeSingleEqf(ClassicWinampPresets.ROCK)
    bytes[298] = 255.toByte()
    codec.parseSingleEqf(bytes)
  }

  @Test
  fun presetLibraryQ1_roundTripsEmptySingleAndAllClassicPresets() {
    val emptyEncoded = codec.encodePresetLibraryQ1(emptyList())
    assertEquals(0, emptyEncoded.size)
    assertEquals(emptyList<EqPreset>(), codec.parsePresetLibraryQ1(emptyEncoded))

    val singleEncoded = codec.encodePresetLibraryQ1(listOf(ClassicWinampPresets.ROCK))
    assertEquals(299, singleEncoded.size)
    val singleDecoded = codec.parsePresetLibraryQ1(singleEncoded)
    assertEquals(1, singleDecoded.size)
    assertEquals("ROCK", singleDecoded[0].name)

    val allEncoded = codec.encodePresetLibraryQ1(ClassicWinampPresets.ALL)
    assertEquals(ClassicWinampPresets.ALL.size * 299, allEncoded.size)
    val allDecoded = codec.parsePresetLibraryQ1(allEncoded)
    assertEquals(ClassicWinampPresets.ALL.size, allDecoded.size)
    assertEquals(ClassicWinampPresets.ALL.map { it.name }, allDecoded.map { it.name })

    // 2nd pass byte-for-byte identity
    assertArrayEquals(allEncoded, codec.encodePresetLibraryQ1(allDecoded))
  }

  @Test
  fun parsePresetLibraryQ1_supportsClassicSingleHeaderLayoutAndHeaderOnlyEmptyFile() {
    val headerOnly = WinampEqBinaryContainer.HEADER_BYTES.copyOf()
    assertEquals(emptyList<EqPreset>(), codec.parsePresetLibraryQ1(headerOnly))

    val classicBytes =
      buildClassicSingleHeaderLibrary(
        listOf(
          Triple("ClassicA", List(10) { 10 }, 31),
          Triple("ClassicB", List(10) { 45 }, 20),
          Triple("ClassicC", List(10) { 0 }, 63),
        ),
      )
    assertEquals(31 + 3 * 268, classicBytes.size)

    val parsed = codec.parsePresetLibraryQ1(classicBytes)
    assertEquals(listOf("ClassicA", "ClassicB", "ClassicC"), parsed.map { it.name })
    assertEquals(0.0f, parsed[0].preampDb, 0.0f)
    assertEquals(-12.0f, parsed[2].preampDb, 0.0f)
    assertEquals(12.0f, parsed[2].bandsDb[0], 0.0f)
  }

  @Test
  fun parsePresetLibraryQ1_disambiguates269ConcatenatedRecordsVs300ClassicRecordsAt80431Bytes() {
    // 269 * 299 == 31 + 300 * 268 == 80,431 bytes: header check at offset 299 disambiguates.
    val concatenated269 =
      codec.encodePresetLibraryQ1(
        List(269) { idx -> EqPreset("Preset_$idx", 0f, List(WinampEqSpec.BAND_COUNT) { 0f }) },
      )
    assertEquals(80_431, concatenated269.size)
    val parsedConcat = codec.parsePresetLibraryQ1(concatenated269)
    assertEquals(269, parsedConcat.size)
    assertEquals("Preset_268", parsedConcat.last().name)

    val classic300 =
      buildClassicSingleHeaderLibrary(
        List(300) { idx -> Triple("Classic_$idx", List(10) { 31 }, 31) },
      )
    assertEquals(80_431, classic300.size)
    val parsedClassic = codec.parsePresetLibraryQ1(classic300)
    assertEquals(300, parsedClassic.size)
    assertEquals("Classic_299", parsedClassic.last().name)
  }

  @Test(expected = IllegalArgumentException::class)
  fun parsePresetLibraryQ1_rejectsInvalidSize() {
    val bytes = WinampEqBinaryContainer.HEADER_BYTES + ByteArray(369) // 400 bytes total
    codec.parsePresetLibraryQ1(bytes)
  }

  private fun buildEqfRecordBytes(name: String, bands: List<Int>, preamp: Int): ByteArray {
    val out = ByteArray(299)
    WinampEqBinaryContainer.HEADER_BYTES.copyInto(out)
    name.toByteArray(Charsets.UTF_8).copyInto(out, destinationOffset = 31)
    bands.forEachIndexed { idx, value -> out[288 + idx] = value.toByte() }
    out[298] = preamp.toByte()
    return out
  }

  private fun buildClassicSingleHeaderLibrary(
    entries: List<Triple<String, List<Int>, Int>>,
  ): ByteArray {
    val out = ByteArray(31 + entries.size * 268)
    WinampEqBinaryContainer.HEADER_BYTES.copyInto(out)
    entries.forEachIndexed { idx, (name, bands, preamp) ->
      val base = 31 + idx * 268
      name.toByteArray(Charsets.UTF_8).copyInto(out, destinationOffset = base)
      bands.forEachIndexed { bandIdx, value -> out[base + 257 + bandIdx] = value.toByte() }
      out[base + 267] = preamp.toByte()
    }
    return out
  }
}
