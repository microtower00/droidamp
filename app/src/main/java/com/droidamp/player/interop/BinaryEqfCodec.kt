package com.droidamp.player.interop

import com.droidamp.player.domain.contract.EqfCodec
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.WinampEqSpec

internal data class WinampEqBinaryRecord(
  val label: String,
  val preampDb: Float,
  val bandsDb: List<Float>,
)

internal object WinampEqBinaryContainer {
  private val HEADER_TEXT_BYTES = "Winamp EQ library file v1.1".toByteArray(Charsets.US_ASCII)
  private val HEADER_SUFFIX_BYTES =
    byteArrayOf(0x1A, '!'.code.toByte(), '-'.code.toByte(), '-'.code.toByte())
  val HEADER_BYTES: ByteArray = HEADER_TEXT_BYTES + HEADER_SUFFIX_BYTES

  const val HEADER_SIZE = 31
  const val NAME_FIELD_SIZE = 257
  const val BAND_COUNT = WinampEqSpec.BAND_COUNT // 10
  const val PAYLOAD_SIZE = NAME_FIELD_SIZE + BAND_COUNT + 1 // 268
  const val RECORD_SIZE = HEADER_SIZE + PAYLOAD_SIZE // 299

  private const val MAX_NAME_BYTES = NAME_FIELD_SIZE - 1 // 256 (always leave >= 1 null terminator)

  fun hasHeaderAt(bytes: ByteArray, offset: Int): Boolean {
    if (offset < 0 || offset + HEADER_SIZE > bytes.size) return false
    for (i in 0 until HEADER_SIZE) {
      if (bytes[offset + i] != HEADER_BYTES[i]) return false
    }
    return true
  }

  fun encodeSingleRecord(record: WinampEqBinaryRecord): ByteArray {
    require(record.bandsDb.size == BAND_COUNT) {
      "Winamp EQ record requires exactly $BAND_COUNT bands."
    }
    val out = ByteArray(RECORD_SIZE)
    writeRecordAt(out, 0, record)
    return out
  }

  fun encodeLibraryRecords(records: List<WinampEqBinaryRecord>): ByteArray {
    if (records.isEmpty()) return ByteArray(0)
    val out = ByteArray(records.size * RECORD_SIZE)
    records.forEachIndexed { index, record ->
      require(record.bandsDb.size == BAND_COUNT) {
        "Winamp EQ record at index $index requires exactly $BAND_COUNT bands."
      }
      writeRecordAt(out, index * RECORD_SIZE, record)
    }
    return out
  }

  fun parseSingleRecord(bytes: ByteArray): WinampEqBinaryRecord {
    require(bytes.size == RECORD_SIZE) {
      "Invalid .eqf size ${bytes.size}; expected $RECORD_SIZE bytes."
    }
    require(hasHeaderAt(bytes, 0)) {
      "Invalid .eqf header signature."
    }
    return parsePayloadAt(bytes, HEADER_SIZE)
  }

  fun parseLibraryRecords(bytes: ByteArray, formatName: String): List<WinampEqBinaryRecord> {
    if (bytes.isEmpty()) return emptyList()
    require(bytes.size >= HEADER_SIZE && hasHeaderAt(bytes, 0)) {
      "Invalid $formatName header signature."
    }
    if (bytes.size == HEADER_SIZE) return emptyList()

    return if (bytes.size % RECORD_SIZE == 0 &&
      (bytes.size == RECORD_SIZE || hasHeaderAt(bytes, RECORD_SIZE))
    ) {
      val count = bytes.size / RECORD_SIZE
      List(count) { index ->
        val recordOffset = index * RECORD_SIZE
        require(hasHeaderAt(bytes, recordOffset)) {
          "Invalid $formatName record header at offset $recordOffset."
        }
        parsePayloadAt(bytes, recordOffset + HEADER_SIZE)
      }
    } else if ((bytes.size - HEADER_SIZE) % PAYLOAD_SIZE == 0) {
      val count = (bytes.size - HEADER_SIZE) / PAYLOAD_SIZE
      List(count) { index ->
        parsePayloadAt(bytes, HEADER_SIZE + index * PAYLOAD_SIZE)
      }
    } else {
      throw IllegalArgumentException(
        "Invalid $formatName size ${bytes.size}: expected a multiple of $RECORD_SIZE bytes " +
          "or $HEADER_SIZE + N * $PAYLOAD_SIZE bytes.",
      )
    }
  }

  private fun writeRecordAt(dest: ByteArray, offset: Int, record: WinampEqBinaryRecord) {
    HEADER_BYTES.copyInto(dest, destinationOffset = offset)
    writeNullTerminatedUtf8(dest, offset + HEADER_SIZE, record.label)
    val bandsOffset = offset + HEADER_SIZE + NAME_FIELD_SIZE
    for (bandIndex in 0 until BAND_COUNT) {
      dest[bandsOffset + bandIndex] =
        WinampEqByteScale.encodeDbToByte(record.bandsDb[bandIndex]).toByte()
    }
    dest[bandsOffset + BAND_COUNT] = WinampEqByteScale.encodeDbToByte(record.preampDb).toByte()
  }

  private fun parsePayloadAt(bytes: ByteArray, nameOffset: Int): WinampEqBinaryRecord {
    var nulIndex = nameOffset
    val nameEnd = nameOffset + NAME_FIELD_SIZE
    while (nulIndex < nameEnd && bytes[nulIndex] != 0.toByte()) {
      nulIndex++
    }
    val label = bytes.decodeToString(startIndex = nameOffset, endIndex = nulIndex).trim()
    val bandsOffset = nameEnd
    val bandsDb =
      List(BAND_COUNT) { bandIndex ->
        WinampEqByteScale.decodeSignedByteToDb(
          byte = bytes[bandsOffset + bandIndex],
          fieldLabel = "band[$bandIndex]",
        )
      }
    val preampDb =
      WinampEqByteScale.decodeSignedByteToDb(
        byte = bytes[bandsOffset + BAND_COUNT],
        fieldLabel = "preamp",
      )
    return WinampEqBinaryRecord(
      label = label,
      preampDb = preampDb,
      bandsDb = bandsDb,
    )
  }

  private fun writeNullTerminatedUtf8(dest: ByteArray, offset: Int, text: String) {
    val utf8 = text.trim().toByteArray(Charsets.UTF_8)
    var length = utf8.size.coerceAtMost(MAX_NAME_BYTES)
    if (utf8.size > MAX_NAME_BYTES) {
      // Back off if the truncation point splits a multi-byte UTF-8 character.
      while (length > 0 && (utf8[length].toInt() and 0xC0) == 0x80) {
        length--
      }
    }
    if (length > 0) {
      utf8.copyInto(dest, destinationOffset = offset, startIndex = 0, endIndex = length)
    }
  }
}

class BinaryEqfCodec : EqfCodec {
  override fun parseSingleEqf(bytes: ByteArray): EqPreset {
    val record = WinampEqBinaryContainer.parseSingleRecord(bytes)
    return EqPreset(
      name = record.label,
      preampDb = record.preampDb,
      bandsDb = record.bandsDb,
    )
  }

  override fun encodeSingleEqf(preset: EqPreset): ByteArray =
    WinampEqBinaryContainer.encodeSingleRecord(
      WinampEqBinaryRecord(
        label = preset.name,
        preampDb = preset.preampDb,
        bandsDb = preset.bandsDb,
      ),
    )

  override fun parsePresetLibraryQ1(bytes: ByteArray): List<EqPreset> =
    WinampEqBinaryContainer.parseLibraryRecords(bytes, formatName = "winamp.q1")
      .map { record ->
        EqPreset(
          name = record.label,
          preampDb = record.preampDb,
          bandsDb = record.bandsDb,
        )
      }

  override fun encodePresetLibraryQ1(presets: List<EqPreset>): ByteArray =
    WinampEqBinaryContainer.encodeLibraryRecords(
      presets.map { preset ->
        WinampEqBinaryRecord(
          label = preset.name,
          preampDb = preset.preampDb,
          bandsDb = preset.bandsDb,
        )
      },
    )
}
