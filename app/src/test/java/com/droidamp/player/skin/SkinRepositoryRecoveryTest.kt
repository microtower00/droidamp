package com.droidamp.player.skin

import androidx.compose.ui.graphics.Color
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.interop.BinaryEqfCodec
import com.droidamp.player.interop.DetectedWinampFormat
import com.droidamp.player.skin.wsz.FakeJvmSkinBitmapDecoder
import com.droidamp.player.skin.wsz.WszSkinLoader
import com.droidamp.player.skin.wsz.WszSkinParserTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class SkinRepositoryRecoveryTest {
  private val loader = WszSkinLoader(decoder = FakeJvmSkinBitmapDecoder())

  @Test
  fun safeBootCrashMarkerRevertsToClassicAndRetryRecovers() = runTest {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val gateway = FakeSkinStorageGateway()
    val validSolarZip =
      WszSkinParserTest.buildTestZip(
        "droidamp.json" to """{"schemaVersion":1,"name":"Solar Rack","author":"Test"}""".toByteArray(),
      )
    gateway.writeImportedSkin("solar.wsz", validSolarZip)

    val prefs =
      InMemorySkinPreferencesStore(
        activeId = "imported:solar.wsz",
        pendingBootSkinId = "imported:solar.wsz",
      )

    val repo =
      AndroidSkinRepository(
        storageGateway = gateway,
        preferencesStore = prefs,
        scope = CoroutineScope(dispatcher),
        loader = loader,
        ioDispatcher = dispatcher,
      )
    testScheduler.advanceUntilIdle()

    // Safe-boot guard intercepted the un-cleared marker and reverted to DroidAmp Classic
    assertEquals(ClassicDroidAmpSkin.id, repo.activeSkin.value.id)
    assertEquals(ClassicDroidAmpSkin.id, prefs.loadActiveSkinId())
    assertNull(prefs.loadPendingBootSkinId())
    val notice = repo.recoveryNotice.value
    assertTrue(notice is SkinRecoveryNotice.SafeBootReverted)
    notice as SkinRecoveryNotice.SafeBootReverted
    assertEquals("imported:solar.wsz", notice.failedSkinId)
    assertEquals("Solar Rack", notice.failedSkinName)

    // User chooses Retry -> loads cleanly, activates skin, clears pendingBootSkinId & notice
    repo.retrySafeBootSkin("imported:solar.wsz")
    testScheduler.advanceUntilIdle()
    assertEquals("imported:solar.wsz", repo.activeSkin.value.id)
    assertEquals("Solar Rack", repo.activeSkin.value.displayName)
    assertEquals("imported:solar.wsz", prefs.loadActiveSkinId())
    assertNull(prefs.loadPendingBootSkinId())
    assertNull(repo.recoveryNotice.value)
  }

  @Test
  fun coldBootDecodeFailureRevertsCleanlyToSafeBoot() = runTest {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val gateway = FakeSkinStorageGateway()
    // Corrupt non-zip bytes stored under imported skin
    gateway.writeImportedSkin("corrupt.wsz", byteArrayOf(0, 1, 2, 3, 4))

    val prefs = InMemorySkinPreferencesStore(activeId = "imported:corrupt.wsz")
    val repo =
      AndroidSkinRepository(
        storageGateway = gateway,
        preferencesStore = prefs,
        scope = CoroutineScope(dispatcher),
        loader = loader,
        ioDispatcher = dispatcher,
      )
    testScheduler.advanceUntilIdle()

    assertEquals(ClassicDroidAmpSkin.id, repo.activeSkin.value.id)
    assertEquals(ClassicDroidAmpSkin.id, prefs.loadActiveSkinId())
    assertNull(prefs.loadPendingBootSkinId())
    assertTrue(repo.recoveryNotice.value is SkinRecoveryNotice.SafeBootReverted)
  }

  @Test
  fun wrongSlotEqfImportEmitsRedirectNoticeWithoutCorruptingSkins() = runTest {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val gateway = FakeSkinStorageGateway()
    val eqfBytes =
      BinaryEqfCodec().encodeSingleEqf(
        EqPreset(
          name = "Club Punch",
          preampDb = 0f,
          bandsDb = List(10) { 2f },
        ),
      )
    gateway.putExternalUri("content://downloads/club_punch.eqf", "club_punch.eqf", eqfBytes)

    val repo =
      AndroidSkinRepository(
        storageGateway = gateway,
        preferencesStore = InMemorySkinPreferencesStore(),
        scope = CoroutineScope(dispatcher),
        loader = loader,
        ioDispatcher = dispatcher,
      )

    val result = repo.importSkinFromUri("content://downloads/club_punch.eqf")
    assertTrue(result.isSuccess)
    assertNull(result.getOrNull())

    val notice = repo.recoveryNotice.value
    assertTrue(notice is SkinRecoveryNotice.WrongSlotFileDetected)
    notice as SkinRecoveryNotice.WrongSlotFileDetected
    assertEquals("club_punch.eqf", notice.fileName)
    assertEquals(DetectedWinampFormat.EqfSinglePreset, notice.detectedFormat)
    assertTrue(notice.rawBytes.contentEquals(eqfBytes))
    assertTrue(gateway.listImportedSkins().isEmpty())
  }

  @Test
  fun duplicateImportSupportsReplaceAndKeepBothAndActiveDeleteReverts() = runTest {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val gateway = FakeSkinStorageGateway()
    val prefs = InMemorySkinPreferencesStore()

    val v1Bytes =
      WszSkinParserTest.buildTestZip(
        "droidamp.json" to """{"schemaVersion":1,"name":"Cyber v1"}""".toByteArray(),
      )
    val v2Bytes =
      WszSkinParserTest.buildTestZip(
        "droidamp.json" to """{"schemaVersion":1,"name":"Cyber v2"}""".toByteArray(),
      )
    val v3Bytes =
      WszSkinParserTest.buildTestZip(
        "droidamp.json" to """{"schemaVersion":1,"name":"Cyber v3"}""".toByteArray(),
      )

    gateway.putExternalUri("content://picker/1", "cyber.wsz", v1Bytes)
    gateway.putExternalUri("content://picker/2", "cyber.wsz", v2Bytes)
    gateway.putExternalUri("content://picker/3", "cyber.wsz", v3Bytes)

    val repo =
      AndroidSkinRepository(
        storageGateway = gateway,
        preferencesStore = prefs,
        scope = CoroutineScope(dispatcher),
        loader = loader,
        ioDispatcher = dispatcher,
      )

    // Initial import succeeds directly
    val first = repo.importSkinFromUri("content://picker/1").getOrThrow()
    assertNotNull(first)
    assertEquals("imported:cyber.wsz", first!!.id)
    assertEquals("Cyber v1", repo.activeSkin.value.displayName)

    // Second import with same filename pauses on DuplicateSkinConflict
    val secondAttempt = repo.importSkinFromUri("content://picker/2").getOrThrow()
    assertNull(secondAttempt)
    val conflict1 = repo.recoveryNotice.value as SkinRecoveryNotice.DuplicateSkinConflict
    assertEquals("cyber.wsz", conflict1.proposedFileName)

    // Resolve with replaceExisting = true -> overwrites imported:cyber.wsz in place
    val replaced = repo.resolveDuplicateImport(conflict1, replaceExisting = true).getOrThrow()
    assertEquals("imported:cyber.wsz", replaced.id)
    assertEquals("Cyber v2", repo.activeSkin.value.displayName)
    assertEquals(1, gateway.listImportedSkins().size)

    // Third import -> resolve with replaceExisting = false ("Keep Both") -> creates cyber_2.wsz
    assertNull(repo.importSkinFromUri("content://picker/3").getOrThrow())
    val conflict2 = repo.recoveryNotice.value as SkinRecoveryNotice.DuplicateSkinConflict
    val keptBoth = repo.resolveDuplicateImport(conflict2, replaceExisting = false).getOrThrow()
    assertEquals("imported:cyber_2.wsz", keptBoth.id)
    assertEquals("Cyber v3", repo.activeSkin.value.displayName)
    assertEquals(2, gateway.listImportedSkins().size)

    // Deleting active skin reverts immediately to DroidAmp Classic + emits ActiveSkinDeletedReverted
    assertTrue(repo.deleteImportedSkin("imported:cyber_2.wsz"))
    assertEquals(ClassicDroidAmpSkin.id, repo.activeSkin.value.id)
    assertEquals(ClassicDroidAmpSkin.id, prefs.loadActiveSkinId())
    val delNotice = repo.recoveryNotice.value as SkinRecoveryNotice.ActiveSkinDeletedReverted
    assertEquals("Cyber v3", delNotice.deletedSkinName)
  }

  @Test
  fun discoversShowcaseSkinsAndReloadsActiveSkinLiveOnRenderAndContrastToggles() = runTest {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val gateway = FakeSkinStorageGateway()
    val prefs = InMemorySkinPreferencesStore()

    gateway.putBundledShowcase(
      "amber_hifi_rack.wsz",
      WszSkinParserTest.buildTestZip(
        "droidamp.json" to """{"schemaVersion":1,"name":"Amber Hi-Fi Rack","author":"DroidAmp Studio"}""".toByteArray(),
      ),
    )

    val legacyWithLowContrast =
      WszSkinParserTest.buildTestZip(
        "main.bmp" to WszSkinParserTest.fakeImageTag(275, 116),
        "cbuttons.bmp" to WszSkinParserTest.fakeImageTag(136, 36),
        "titlebar.bmp" to WszSkinParserTest.fakeImageTag(344, 87),
        "eqmain.bmp" to WszSkinParserTest.fakeImageTag(275, 315),
        "pledit.bmp" to WszSkinParserTest.fakeImageTag(280, 186),
        "pledit.txt" to "[Text]\r\nNormal=#141A14\r\nCurrent=#182018\r\nNormalBG=#101210\r\n".toByteArray(),
      )
    gateway.putExternalUri("content://picker/stealth.wsz", "stealth.wsz", legacyWithLowContrast)

    val repo =
      AndroidSkinRepository(
        storageGateway = gateway,
        preferencesStore = prefs,
        scope = CoroutineScope(dispatcher),
        loader = loader,
        ioDispatcher = dispatcher,
      )
    testScheduler.advanceUntilIdle()

    // Showcase skin discovered with AUGMENTED_WSZ origin and manifest metadata
    val showcase = repo.availableSkins.value.firstOrNull { it.source == SkinSource.BUILT_IN_SHOWCASE }
    assertNotNull(showcase)
    assertEquals("Amber Hi-Fi Rack", showcase!!.displayName)
    assertEquals("DroidAmp Studio", showcase.author)
    assertEquals(SkinOrigin.AUGMENTED_WSZ, showcase.origin)

    // Import low-contrast legacy skin -> ContrastGuardApplied notice + VECTOR_CHROME (empty sprites)
    val imported = repo.importSkinFromUri("content://picker/stealth.wsz").getOrThrow()!!
    assertEquals(SkinOrigin.LEGACY_WSZ, imported.origin)
    assertTrue(repo.activeSkin.value.sprites.transportNormal.isEmpty())
    assertTrue(repo.recoveryNotice.value is SkinRecoveryNotice.ContrastGuardApplied)
    assertFalse(Color(0xFF141A14) == repo.activeSkin.value.playlist.normalText)

    // Live toggle to CLASSIC_RAW_BMP -> immediately reloads active skin with BMP sprites
    repo.setLegacyRenderMode(SkinRenderMode.CLASSIC_RAW_BMP)
    testScheduler.advanceUntilIdle()
    assertEquals(6, repo.activeSkin.value.sprites.transportNormal.size)
    assertTrue(repo.activeSkin.value.sprites.hasCompletePanelHeaders)

    // Per-skin raw OG colors override -> immediately reloads active skin with exact raw OG hex color
    repo.setSkinRawColorsOverride(imported.id, useRawColors = true)
    testScheduler.advanceUntilIdle()
    assertEquals(Color(0xFF141A14), repo.activeSkin.value.playlist.normalText)
  }

  @Test
  fun deferredSafSkinHydratesOnRefreshSkinsWithoutFalseSafeBootRevert() = runTest {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val gateway = FakeSkinStorageGateway()
    val safUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FWinamp3_Classified_v5.5.wsz"
    val safId = "saf:$safUri"
    val safZip =
      WszSkinParserTest.buildTestZip(
        "droidamp.json" to """{"schemaVersion":1,"name":"Winamp3 Classified","author":"Nullsoft"}""".toByteArray(),
      )
    gateway.putSafRoot(
      rootUri = "content://tree/music",
      skins = listOf(StoredSkinFile("Winamp3_Classified_v5.5.wsz", safUri)),
    )
    gateway.putExternalUri(safUri, "Winamp3_Classified_v5.5.wsz", safZip)

    val prefs = InMemorySkinPreferencesStore(activeId = safId)
    val repo =
      AndroidSkinRepository(
        storageGateway = gateway,
        preferencesStore = prefs,
        scope = CoroutineScope(dispatcher),
        loader = loader,
        ioDispatcher = dispatcher,
      )
    testScheduler.advanceUntilIdle()

    // Before SAF roots hydrate, activeSkin stays Classic without falsely wiping activeId or emitting SafeBootReverted
    assertNull(repo.recoveryNotice.value)
    assertEquals(safId, prefs.loadActiveSkinId())

    // Once SAF roots hydrate via refreshSkins, SAF skin loads cleanly
    repo.refreshSkins(listOf("content://tree/music"))
    testScheduler.advanceUntilIdle()
    assertEquals(safId, repo.activeSkin.value.id)
    assertEquals("Winamp3 Classified", repo.activeSkin.value.displayName)
    assertNull(repo.recoveryNotice.value)

    // Verify readable SAF filename decoding if pendingBootSkinId crash marker is present
    val crashedRepo =
      AndroidSkinRepository(
        storageGateway = gateway,
        preferencesStore = InMemorySkinPreferencesStore(activeId = safId, pendingBootSkinId = safId),
        scope = CoroutineScope(dispatcher),
        loader = loader,
        ioDispatcher = dispatcher,
      )
    testScheduler.advanceUntilIdle()
    val safeNotice = crashedRepo.recoveryNotice.value as SkinRecoveryNotice.SafeBootReverted
    assertEquals("Winamp3 Classified v5.5", safeNotice.failedSkinName)
  }

  private class FakeSkinStorageGateway : SkinStorageGateway {
    private val bundled = LinkedHashMap<String, ByteArray>()
    private val imported = LinkedHashMap<String, ByteArray>()
    private val externalUris = LinkedHashMap<String, Pair<String, ByteArray>>()
    private val safRoots = LinkedHashMap<String, List<StoredSkinFile>>()

    fun putBundledShowcase(fileName: String, bytes: ByteArray) {
      bundled[fileName] = bytes
    }

    fun putExternalUri(uriString: String, displayName: String, bytes: ByteArray) {
      externalUris[uriString] = displayName to bytes
    }

    fun putSafRoot(rootUri: String, skins: List<StoredSkinFile>) {
      safRoots[rootUri] = skins
    }

    override fun listBundledShowcaseSkins(): List<String> =
      bundled.keys.sortedBy { it.lowercase(Locale.ROOT) }

    override fun readBundledShowcaseSkin(fileName: String): ByteArray? = bundled[fileName]

    override fun listImportedSkins(): List<StoredSkinFile> =
      imported.keys
        .sortedBy { it.lowercase(Locale.ROOT) }
        .map { StoredSkinFile(fileName = it, pathOrUri = "/fake/files/skins/$it") }

    override fun readImportedSkin(fileName: String): ByteArray? = imported[fileName]

    override fun writeImportedSkin(fileName: String, bytes: ByteArray): StoredSkinFile {
      imported[fileName] = bytes.copyOf()
      return StoredSkinFile(fileName = fileName, pathOrUri = "/fake/files/skins/$fileName")
    }

    override fun deleteImportedSkin(fileName: String): Boolean = imported.remove(fileName) != null

    override fun listSafSkins(rootUriString: String): List<StoredSkinFile> =
      safRoots[rootUriString].orEmpty()

    override fun readExternalUriBytes(uriString: String): ByteArray? = externalUris[uriString]?.second

    override fun resolveExternalDisplayName(uriString: String): String? = externalUris[uriString]?.first
  }
}
