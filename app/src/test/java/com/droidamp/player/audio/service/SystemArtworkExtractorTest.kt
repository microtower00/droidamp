package com.droidamp.player.audio.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemArtworkExtractorTest {

  @Test
  fun selectCoverArtFileName_prioritizesCoverThenFolderThenAlbumThenFront_caseInsensitive() {
    val mixed = listOf("notes.txt", "FRONT.JPG", "album.jpg", "Folder.Png", "track01.mp3")
    assertEquals("Folder.Png", SystemArtworkExtractor.selectCoverArtFileName(mixed))

    val withCover = listOf("front.jpg", "folder.jpg", "COVER.PNG", "cover.jpg")
    assertEquals("cover.jpg", SystemArtworkExtractor.selectCoverArtFileName(withCover))

    val coverPngOnly = listOf("front.jpg", "COVER.PNG", "album.jpg")
    assertEquals("COVER.PNG", SystemArtworkExtractor.selectCoverArtFileName(coverPngOnly))

    val albumOnly = listOf("track.flac", "Album.JPG", "front.jpg")
    assertEquals("Album.JPG", SystemArtworkExtractor.selectCoverArtFileName(albumOnly))

    val frontOnly = listOf("track.flac", "Front.jpg")
    assertEquals("Front.jpg", SystemArtworkExtractor.selectCoverArtFileName(frontOnly))
  }

  @Test
  fun selectCoverArtFileName_returnsNullWhenNoMatchOrBlank() {
    assertNull(SystemArtworkExtractor.selectCoverArtFileName(emptyList()))
    assertNull(SystemArtworkExtractor.selectCoverArtFileName(listOf("", "   ", "song.mp3", "readme.md")))
  }

  @Test
  fun computeInSampleSize_downscalesOversizedDimensionsByPowersOfTwo() {
    assertEquals(1, SystemArtworkExtractor.computeInSampleSize(512, 512, 512))
    assertEquals(2, SystemArtworkExtractor.computeInSampleSize(1024, 768, 512))
    assertEquals(4, SystemArtworkExtractor.computeInSampleSize(2048, 2048, 512))
    assertEquals(1, SystemArtworkExtractor.computeInSampleSize(0, 500, 512))
  }
}
