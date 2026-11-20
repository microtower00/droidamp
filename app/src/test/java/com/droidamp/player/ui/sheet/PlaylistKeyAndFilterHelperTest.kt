package com.droidamp.player.ui.sheet

import com.droidamp.player.domain.model.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistKeyAndFilterHelperTest {

  private fun track(id: String, title: String, fileName: String) =
    AudioTrack(
      id = id,
      uriString = "content://tree/$fileName",
      title = title,
      artist = "Artist",
      album = "Album",
      fileName = fileName,
      folderPath = "Music",
      durationMs = 180_000L,
    )

  @Test
  fun stablePlaylistRowKeys_areUniqueWhenSameTrackAppearsMultipleTimes() {
    val trackA = track(id = "dup-1", title = "One More Time", fileName = "01_one_more_time.mp3")
    val trackB = track(id = "uniq-2", title = "Aerodynamic", fileName = "02_aerodynamic.mp3")

    val entries = buildPlaylistDisplayEntries(listOf(trackA, trackB, trackA), jumpFilterQuery = "")

    assertEquals(3, entries.size)
    assertEquals(listOf(0, 1, 2), entries.map { it.originalIndex })
    assertEquals(3, entries.map { it.rowKey }.toSet().size)
    assertEquals(listOf("dup-1#0", "uniq-2#0", "dup-1#1"), entries.map { it.rowKey })
  }

  @Test
  fun stablePlaylistRowKeys_preserveOriginalIndicesAcrossJumpFilter() {
    val trackA = track(id = "trk-1", title = "One More Time", fileName = "01_one_more_time.mp3")
    val trackB = track(id = "trk-2", title = "Digital Love", fileName = "02_digital_love.mp3")
    val trackC = track(id = "trk-1", title = "One More Time", fileName = "01_one_more_time.mp3")

    val filtered = buildPlaylistDisplayEntries(listOf(trackA, trackB, trackC), jumpFilterQuery = "digital")

    assertEquals(1, filtered.size)
    assertEquals(1, filtered.first().originalIndex)
    assertEquals("trk-2#0", filtered.first().rowKey)
  }
}
