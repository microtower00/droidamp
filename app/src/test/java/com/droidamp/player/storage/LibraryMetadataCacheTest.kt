package com.droidamp.player.storage

import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.StorageBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMetadataCacheTest {

  private class InMemoryCacheStorage : LibraryCacheStorage {
    val files = LinkedHashMap<String, String>()
    override fun readText(key: String): String? = files[key]
    override fun writeText(key: String, content: String) {
      files[key] = content
    }
    override fun delete(key: String) {
      files.remove(key)
    }
  }

  private fun sampleTrack(
    id: String,
    title: String = "Title\tOne",
    folder: String = "Music/Synth",
  ) = AudioTrack(
    id = id,
    uriString = "content://tree/music/$id.flac",
    title = title,
    artist = "Artist %100",
    album = "Album\nLine",
    fileName = "$id.flac",
    folderPath = folder,
    durationMs = 215_000L,
    bitrateKbps = 920,
    sampleRateHz = 44_100,
    channelMode = ChannelMode.MONO,
  )

  @Test
  fun saveLoadDiffAndPrune_roundTripSafelyWithEscapedFields() {
    val storage = InMemoryCacheStorage()
    val cache = LibraryMetadataCache(storage)

    val root = FolderEntry("content://tree/music", "My\tMusic", listOf("My\tMusic"), 2, 1)
    val t1 = sampleTrack("trk1")
    val t2 = sampleTrack("trk2", title = "Normal Track")

    cache.save(
      CachedLibrarySnapshot(
        backend = StorageBackend.SAF,
        roots = listOf(root),
        tracks = listOf(t1, t2),
        updatedAtEpochMs = 1_700_000_000L,
      ),
    )

    val loaded = cache.load(StorageBackend.SAF)
    assertNotNull(loaded)
    assertEquals(1_700_000_000L, loaded!!.updatedAtEpochMs)
    assertEquals(listOf(root), loaded.roots)
    assertEquals(listOf(t1, t2), loaded.tracks)

    // Separate backend stays independent
    assertNull(cache.load(StorageBackend.MEDIA_STORE))

    // Diff detects added & removed URIs cleanly
    val t3 = sampleTrack("trk3")
    val fresh =
      CachedLibrarySnapshot(
        backend = StorageBackend.SAF,
        roots = listOf(root),
        tracks = listOf(t2, t3),
      )
    val diff = cache.computeDiff(loaded, fresh)
    assertEquals(listOf(t3), diff.addedTracks)
    assertEquals(setOf(t1.uriString), diff.removedTrackUris)

    // Prune single missing track URI
    val pruned = cache.pruneTrackUri(StorageBackend.SAF, t1.uriString)
    assertEquals(listOf(t2), pruned!!.tracks)
    assertEquals(listOf(t2), cache.load(StorageBackend.SAF)!!.tracks)

    cache.clear(StorageBackend.SAF)
    assertNull(cache.load(StorageBackend.SAF))
  }

  @Test
  fun corruptCacheHeader_returnsNullWithoutThrowing() {
    val storage = InMemoryCacheStorage()
    storage.writeText("saf_index.tsv", "GARBAGE_HEADER\nTRACK\tbad")
    val cache = LibraryMetadataCache(storage)

    assertNull(cache.load(StorageBackend.SAF))
    assertTrue(cache.computeDiff(null, CachedLibrarySnapshot(StorageBackend.SAF, emptyList(), emptyList())).addedTracks.isEmpty())
  }
}
