package com.droidamp.player.interop

import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserEqPresetStoreTest {

  private class InMemoryByteStore : ByteStoreGateway {
    val data = LinkedHashMap<String, ByteArray>()
    override fun readBytes(fileName: String): ByteArray? = data[fileName]
    override fun writeBytes(fileName: String, bytes: ByteArray) {
      data[fileName] = bytes
    }
    override fun delete(fileName: String) {
      data.remove(fileName)
    }
  }

  @Test
  fun saveCollisionDeleteMergeAndDiskReload_workAcrossInstances() = runTest {
    val gateway = InMemoryByteStore()
    val store1 = UserEqPresetStore(gateway)
    store1.ensureLoaded()

    val night = EqPreset("Late Night", 1.5f, listOf(4f, 3f, 2f, 0f, -1f, -1f, 0f, 1f, 2f, 3f))
    assertEquals(PresetConflictType.NONE, store1.hasPresetConflict("Late Night"))
    assertEquals(PresetConflictType.BUILT_IN_PRESET, store1.hasPresetConflict(ClassicWinampPresets.ROCK.name))

    assertTrue(store1.savePreset(night, overwrite = false).isSuccess)
    assertEquals(PresetConflictType.USER_PRESET, store1.hasPresetConflict("late night"))
    assertTrue(store1.savePreset(night.copy(preampDb = -2f), overwrite = false).isFailure)
    assertTrue(store1.savePreset(night.copy(preampDb = -2f), overwrite = true).isSuccess)

    store1.saveAutoRule(AutoEqRule("synth.flac", night))

    // Reload in a fresh store instance from binary .q1/.q2 files
    val store2 = UserEqPresetStore(gateway)
    store2.ensureLoaded()
    assertEquals(1, store2.userPresets.value.size)
    assertEquals("Late Night", store2.userPresets.value.first().name)
    assertEquals(-2f, store2.userPresets.value.first().preampDb, 0.25f)
    assertEquals(1, store2.userAutoEqRules.value.size)

    // Merge vs Replace All
    val extra = listOf(EqPreset("Vinyl Warmth", 0f, List(10) { 1f }))
    store2.importPresets(extra, replaceAll = false)
    assertEquals(2, store2.userPresets.value.size)
    store2.importPresets(extra, replaceAll = true)
    assertEquals(1, store2.userPresets.value.size)

    assertTrue(store2.deletePreset("Vinyl Warmth"))
    assertFalse(store2.deletePreset("Nonexistent"))
    assertTrue(store2.userPresets.value.isEmpty())
  }
}
