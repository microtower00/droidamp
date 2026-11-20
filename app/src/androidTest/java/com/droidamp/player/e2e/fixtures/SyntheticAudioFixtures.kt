package com.droidamp.player.e2e.fixtures

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

object SyntheticAudioFixtures {

  val SUPPORTED_WAV_SAMPLE_RATES = intArrayOf(22_050, 32_000, 44_100, 48_000)
  val SUPPORTED_MP1_SAMPLE_RATES = intArrayOf(32_000, 44_100, 48_000)
  val SUPPORTED_MP1_BITRATES_KBPS = intArrayOf(
    32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320,
  )

  data class Id3MetadataSpec(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val replayGainTrackGainDb: String? = null, // e.g. "-6.50 dB"
    val replayGainTrackPeak: String? = null,   // e.g. "0.988123"
    val replayGainAlbumGainDb: String? = null, // e.g. "-5.20 dB"
    val replayGainAlbumPeak: String? = null,   // e.g. "1.000000"
    val artworkPngBytes: ByteArray? = null,
    val extraTxxx: Map<String, String> = emptyMap(),
  )

  enum class WavCorruption {
    TRUNCATED_HEADER,   // 20 bytes (< 44-byte RIFF header)
    BAD_RIFF_MAGIC,     // "BADR" instead of "RIFF"
    BAD_WAVE_MAGIC,     // "NOPE" instead of "WAVE"
    TRUNCATED_PAYLOAD,  // Valid 44-byte header claiming 44100 bytes, only 16 data bytes written
    EMPTY_DATA_CHUNK,   // Valid 44-byte header with dataSize = 0
  }

  enum class Mp3Corruption {
    BAD_SYNC_WORD,      // 0x00 0x00 instead of 0xFF 0xFB
    TRUNCATED_FRAME,    // Valid ID3 + partial first MPEG frame
    CORRUPT_ID3_SIZE,   // ID3 header claiming ~10_000 bytes on a 12-byte file
  }

  // ---- WAV (RIFF / 16-bit Signed LE PCM) ----

  fun generateWav(
    sampleRateHz: Int = 44_100,
    channels: Int = 2,
    durationMs: Long = 500L,
    leftFrequencyHz: Double = 440.0,
    rightFrequencyHz: Double = leftFrequencyHz,
    leftAmplitude: Double = 0.5,
    rightAmplitude: Double = leftAmplitude,
  ): ByteArray {
    require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
    require(channels == 1 || channels == 2) { "channels must be 1 or 2" }
    require(durationMs >= 0L) { "durationMs must be >= 0" }

    val frameCount = ((sampleRateHz.toLong() * durationMs) / 1000L).toInt().coerceAtLeast(0)
    val blockAlign = channels * 2
    val dataSize = frameCount * blockAlign
    val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

    // RIFF chunk descriptor
    buffer.put('R'.code.toByte()).put('I'.code.toByte()).put('F'.code.toByte()).put('F'.code.toByte())
    buffer.putInt(36 + dataSize)
    buffer.put('W'.code.toByte()).put('A'.code.toByte()).put('V'.code.toByte()).put('E'.code.toByte())

    // fmt sub-chunk (16 bytes, audioFormat = 1 PCM)
    buffer.put('f'.code.toByte()).put('m'.code.toByte()).put('t'.code.toByte()).put(' '.code.toByte())
    buffer.putInt(16)
    buffer.putShort(1.toShort())
    buffer.putShort(channels.toShort())
    buffer.putInt(sampleRateHz)
    buffer.putInt(sampleRateHz * blockAlign)
    buffer.putShort(blockAlign.toShort())
    buffer.putShort(16.toShort())

    // data sub-chunk
    buffer.put('d'.code.toByte()).put('a'.code.toByte()).put('t'.code.toByte()).put('a'.code.toByte())
    buffer.putInt(dataSize)

    for (i in 0 until frameCount) {
      val lSample = sineSample16(i, sampleRateHz, leftFrequencyHz, leftAmplitude)
      buffer.putShort(lSample)
      if (channels == 2) {
        val rSample = sineSample16(i, sampleRateHz, rightFrequencyHz, rightAmplitude)
        buffer.putShort(rSample)
      }
    }
    return buffer.array()
  }

  fun generateSilenceWav(
    sampleRateHz: Int = 44_100,
    channels: Int = 2,
    durationMs: Long = 500L,
  ): ByteArray = generateWav(
    sampleRateHz = sampleRateHz,
    channels = channels,
    durationMs = durationMs,
    leftAmplitude = 0.0,
    rightAmplitude = 0.0,
  )

  fun generateClippingWav(
    sampleRateHz: Int = 44_100,
    channels: Int = 2,
    durationMs: Long = 500L,
    frequencyHz: Double = 1000.0,
  ): ByteArray = generateWav(
    sampleRateHz = sampleRateHz,
    channels = channels,
    durationMs = durationMs,
    leftFrequencyHz = frequencyHz,
    rightFrequencyHz = frequencyHz,
    leftAmplitude = 1.5, // intentionally clipped to [-32768, 32767]
    rightAmplitude = 1.5,
  )

  fun generateCorruptedWav(corruption: WavCorruption): ByteArray = when (corruption) {
    WavCorruption.TRUNCATED_HEADER ->
      generateWav(durationMs = 100L).copyOf(20)
    WavCorruption.BAD_RIFF_MAGIC ->
      generateWav(durationMs = 100L).apply {
        this[0] = 'B'.code.toByte()
        this[1] = 'A'.code.toByte()
        this[2] = 'D'.code.toByte()
        this[3] = 'R'.code.toByte()
      }
    WavCorruption.BAD_WAVE_MAGIC ->
      generateWav(durationMs = 100L).apply {
        this[8] = 'N'.code.toByte()
        this[9] = 'O'.code.toByte()
        this[10] = 'P'.code.toByte()
        this[11] = 'E'.code.toByte()
      }
    WavCorruption.TRUNCATED_PAYLOAD ->
      generateWav(sampleRateHz = 44_100, channels = 2, durationMs = 250L).copyOf(44 + 16)
    WavCorruption.EMPTY_DATA_CHUNK ->
      generateWav(sampleRateHz = 44_100, channels = 2, durationMs = 0L)
  }

  private fun sineSample16(
    frameIndex: Int,
    sampleRateHz: Int,
    frequencyHz: Double,
    amplitude: Double,
  ): Short {
    if (amplitude == 0.0 || frequencyHz <= 0.0) return 0
    val raw = sin(2.0 * PI * frequencyHz * frameIndex / sampleRateHz) * amplitude * 32767.0
    return raw.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
  }

  // ---- MPEG-1 Layer III (.mp3) + ID3v2.3 + 1x1 PNG ----

  fun generateMinimal1x1Png(
    red: Int = 0x28,
    green: Int = 0xF0,
    blue: Int = 0x28,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    // PNG 8-byte signature
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

    // IHDR (13 bytes): 1x1, 8-bit, RGB (colorType=2)
    val ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
      .putInt(1).putInt(1)
      .put(8).put(2).put(0).put(0).put(0)
      .array()
    writePngChunk(out, "IHDR", ihdr)

    // IDAT: zlib-compressed 1x1 scanline [filter=0, R, G, B]
    val rawScanline = byteArrayOf(0, red.toByte(), green.toByte(), blue.toByte())
    val deflater = Deflater()
    val compressed = ByteArrayOutputStream()
    try {
      deflater.setInput(rawScanline)
      deflater.finish()
      val tmp = ByteArray(64)
      while (!deflater.finished()) {
        val count = deflater.deflate(tmp)
        compressed.write(tmp, 0, count)
      }
    } finally {
      deflater.end()
    }
    writePngChunk(out, "IDAT", compressed.toByteArray())

    // IEND
    writePngChunk(out, "IEND", ByteArray(0))
    return out.toByteArray()
  }

  private fun writePngChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    require(typeBytes.size == 4)
    out.write(int32Be(data.size))
    out.write(typeBytes)
    out.write(data)
    val crc = CRC32().apply {
      update(typeBytes)
      update(data)
    }.value.toInt()
    out.write(int32Be(crc))
  }

  fun buildId3v23Tag(metadata: Id3MetadataSpec): ByteArray {
    val useId3v24 = listOfNotNull(metadata.title, metadata.artist, metadata.album)
      .any { text -> text.any { it.code > 0x7F } }

    val frames = ByteArrayOutputStream()
    metadata.title?.let { frames.write(buildTextFrame("TIT2", it, useId3v24)) }
    metadata.artist?.let { frames.write(buildTextFrame("TPE1", it, useId3v24)) }
    metadata.album?.let { frames.write(buildTextFrame("TALB", it, useId3v24)) }

    metadata.replayGainTrackGainDb?.let {
      frames.write(buildTxxxFrame("REPLAYGAIN_TRACK_GAIN", it, useId3v24))
    }
    metadata.replayGainTrackPeak?.let {
      frames.write(buildTxxxFrame("REPLAYGAIN_TRACK_PEAK", it, useId3v24))
    }
    metadata.replayGainAlbumGainDb?.let {
      frames.write(buildTxxxFrame("REPLAYGAIN_ALBUM_GAIN", it, useId3v24))
    }
    metadata.replayGainAlbumPeak?.let {
      frames.write(buildTxxxFrame("REPLAYGAIN_ALBUM_PEAK", it, useId3v24))
    }
    metadata.extraTxxx.forEach { (desc, value) ->
      frames.write(buildTxxxFrame(desc, value, useId3v24))
    }
    metadata.artworkPngBytes?.let { png ->
      frames.write(buildApicFrame("image/png", 0x03, "", png, useId3v24))
    }

    val payload = frames.toByteArray()
    val tag = ByteArrayOutputStream(10 + payload.size)
    tag.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()))
    tag.write(if (useId3v24) byteArrayOf(0x04, 0x00) else byteArrayOf(0x03, 0x00)) // ID3v2.4.0 vs ID3v2.3.0
    tag.write(0x00)                    // flags
    tag.write(encodeSynchsafeInt(payload.size))
    tag.write(payload)
    return tag.toByteArray()
  }

  private fun buildTextFrame(frameId: String, text: String, useId3v24: Boolean): ByteArray {
    val isAscii = text.all { it.code <= 0x7F }
    val payload = when {
      useId3v24 && !isAscii -> byteArrayOf(0x03) + text.toByteArray(Charsets.UTF_8)
      isAscii -> byteArrayOf(0x00) + text.toByteArray(Charsets.ISO_8859_1)
      else -> byteArrayOf(0x01, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
    }
    return wrapId3v2Frame(frameId, payload, useId3v24)
  }

  private fun buildTxxxFrame(description: String, value: String, useId3v24: Boolean): ByteArray {
    val payload = ByteArrayOutputStream()
    payload.write(0x00) // ISO-8859-1
    payload.write(description.toByteArray(Charsets.ISO_8859_1))
    payload.write(0x00) // NUL separator
    payload.write(value.toByteArray(Charsets.ISO_8859_1))
    return wrapId3v2Frame("TXXX", payload.toByteArray(), useId3v24)
  }

  private fun buildApicFrame(
    mimeType: String,
    pictureType: Int,
    description: String,
    imageBytes: ByteArray,
    useId3v24: Boolean,
  ): ByteArray {
    val payload = ByteArrayOutputStream()
    payload.write(0x00) // ISO-8859-1
    payload.write(mimeType.toByteArray(Charsets.ISO_8859_1))
    payload.write(0x00)
    payload.write(pictureType and 0xFF)
    payload.write(description.toByteArray(Charsets.ISO_8859_1))
    payload.write(0x00)
    payload.write(imageBytes)
    return wrapId3v2Frame("APIC", payload.toByteArray(), useId3v24)
  }

  private fun wrapId3v2Frame(frameId: String, payload: ByteArray, useId3v24: Boolean): ByteArray {
    val idBytes = frameId.toByteArray(Charsets.US_ASCII)
    require(idBytes.size == 4)
    val out = ByteArrayOutputStream(10 + payload.size)
    out.write(idBytes)
    out.write(if (useId3v24) encodeSynchsafeInt(payload.size) else int32Be(payload.size))
    out.write(byteArrayOf(0x00, 0x00))
    out.write(payload)
    return out.toByteArray()
  }

  fun generateMp3(
    sampleRateHz: Int = 44_100,
    bitrateKbps: Int = 128,
    channels: Int = 2,
    durationMs: Long = 500L,
    metadata: Id3MetadataSpec? = null,
    includeXingInfoHeader: Boolean = true,
  ): ByteArray {
    require(channels == 1 || channels == 2) { "channels must be 1 or 2" }
    val sampleRateIdx = when (sampleRateHz) {
      44_100 -> 0
      48_000 -> 1
      32_000 -> 2
      else -> throw IllegalArgumentException("MPEG-1 Layer III requires 32000, 44100, or 48000 Hz (got $sampleRateHz)")
    }
    val bitrateIdx = SUPPORTED_MP1_BITRATES_KBPS.indexOf(bitrateKbps).let { idx ->
      require(idx >= 0) { "Unsupported MPEG-1 Layer III bitrate: $bitrateKbps" }
      idx + 1 // table indices 1..14
    }

    val frameSize = (144 * bitrateKbps * 1000) / sampleRateHz // padding = 0
    val samplesPerFrame = 1152
    val audioFrameCount = ceil((durationMs * sampleRateHz.toDouble()) / (samplesPerFrame * 1000.0))
      .toInt()
      .coerceAtLeast(4) // Media3 Mp3Extractor requires >= 4 consecutive sync frames

    val totalFrames = if (includeXingInfoHeader) audioFrameCount + 1 else audioFrameCount
    val out = ByteArrayOutputStream()

    if (metadata != null) {
      out.write(buildId3v23Tag(metadata))
    }

    val headerByte2 = ((bitrateIdx and 0x0F) shl 4) or ((sampleRateIdx and 0x03) shl 2)
    val headerByte3 = if (channels == 1) 0xC0 else 0x00 // Mono vs Stereo

    if (includeXingInfoHeader) {
      val infoFrame = ByteArray(frameSize)
      infoFrame[0] = 0xFF.toByte()
      infoFrame[1] = 0xFB.toByte()
      infoFrame[2] = headerByte2.toByte()
      infoFrame[3] = headerByte3.toByte()
      val sideInfoSize = if (channels == 1) 17 else 32
      val xingOffset = 4 + sideInfoSize
      infoFrame[xingOffset] = 'I'.code.toByte()
      infoFrame[xingOffset + 1] = 'n'.code.toByte()
      infoFrame[xingOffset + 2] = 'f'.code.toByte()
      infoFrame[xingOffset + 3] = 'o'.code.toByte()
      // flags = 0x00000003 (frames + bytes)
      System.arraycopy(int32Be(0x00000003), 0, infoFrame, xingOffset + 4, 4)
      System.arraycopy(int32Be(totalFrames), 0, infoFrame, xingOffset + 8, 4)
      System.arraycopy(int32Be(totalFrames * frameSize), 0, infoFrame, xingOffset + 12, 4)
      out.write(infoFrame)
    }

    val silentFrame = ByteArray(frameSize)
    silentFrame[0] = 0xFF.toByte()
    silentFrame[1] = 0xFB.toByte()
    silentFrame[2] = headerByte2.toByte()
    silentFrame[3] = headerByte3.toByte()

    repeat(audioFrameCount) {
      out.write(silentFrame)
    }
    return out.toByteArray()
  }

  fun generateCorruptedMp3(corruption: Mp3Corruption): ByteArray = when (corruption) {
    Mp3Corruption.BAD_SYNC_WORD -> ByteArray(512) // all zeros, no 0xFF 0xFB sync
    Mp3Corruption.TRUNCATED_FRAME -> generateMp3(durationMs = 150L, includeXingInfoHeader = false).copyOf(120)
    Mp3Corruption.CORRUPT_ID3_SIZE -> byteArrayOf(
      'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
      0x03, 0x00, 0x00,
      0x00, 0x00, 0x4E, 0x20, // synchsafe 10016 bytes
      0x00, 0x00,
    )
  }

  // ---- Temp File Materialization & Cleanup ----

  class FixtureTempScope(val rootDir: File) : Closeable {
    init {
      rootDir.mkdirs()
    }

    fun writeFile(relativePath: String, bytes: ByteArray): File {
      val target = File(rootDir, relativePath)
      target.parentFile?.mkdirs()
      target.writeBytes(bytes)
      return target
    }

    fun writeText(relativePath: String, text: String): File =
      writeFile(relativePath, text.toByteArray(Charsets.UTF_8))

    fun fileUri(file: File): String = Uri.fromFile(file).toString()

    override fun close() {
      rootDir.deleteRecursively()
    }
  }

  fun createCacheScope(context: Context, dirName: String = "e2e_audio_fixtures"): FixtureTempScope =
    FixtureTempScope(File(context.cacheDir, dirName))

  fun createFilesScope(context: Context, dirName: String = "e2e_audio_fixtures"): FixtureTempScope =
    FixtureTempScope(File(context.filesDir, dirName))

  private fun int32Be(value: Int): ByteArray = byteArrayOf(
    ((value ushr 24) and 0xFF).toByte(),
    ((value ushr 16) and 0xFF).toByte(),
    ((value ushr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte(),
  )

  private fun encodeSynchsafeInt(value: Int): ByteArray = byteArrayOf(
    ((value ushr 21) and 0x7F).toByte(),
    ((value ushr 14) and 0x7F).toByte(),
    ((value ushr 7) and 0x7F).toByte(),
    (value and 0x7F).toByte(),
  )
}
