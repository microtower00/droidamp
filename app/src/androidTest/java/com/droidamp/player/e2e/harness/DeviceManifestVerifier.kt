package com.droidamp.player.e2e.harness

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.droidamp.player.skin.ClassicDroidAmpSkin
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Runtime device, manifest permission, MediaSession/AudioManager, fixture lookup,
 * and Compose UI geometry/zero-art verifier for DroidAmp opaque-box E2E tests.
 */
object DeviceManifestVerifier {

  const val DOORS_TRACK_FILE_NAME = "The Doors - L.A. Woman.mp3"
  const val DEFAULT_DOORS_SDCARD_PATH = "/sdcard/Music/$DOORS_TRACK_FILE_NAME"
  private const val MEDIA3_SESSION_SERVICE_ACTION = "androidx.media3.session.MediaSessionService"

  val FORBIDDEN_OFFLINE_PERMISSIONS: Set<String> =
    setOf(
      Manifest.permission.INTERNET,
      Manifest.permission.ACCESS_NETWORK_STATE,
      Manifest.permission.RECORD_AUDIO,
    )

  private val FORBIDDEN_ART_DESCRIPTION_TOKENS =
    listOf("album", "artwork", "cover")

  data class ActiveMediaSessionSnapshot(
    val packageName: String,
    val playbackState: Int?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val hasArtworkBitmapOrUri: Boolean,
  )

  data class AudioDeviceSnapshot(
    val isMusicActive: Boolean,
    val musicStreamVolume: Int,
    val musicStreamMaxVolume: Int,
  )

  // ---------------------------------------------------------------------------
  // 1. Offline Merged-Manifest Permission Audit
  // ---------------------------------------------------------------------------

  fun getRequestedManifestPermissions(
    context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
  ): Set<String> {
    val pm = context.packageManager
    val packageInfo =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(
          context.packageName,
          PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
      } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
      }
    return (packageInfo.requestedPermissions ?: emptyArray()).toSet()
  }

  fun assertOfflineManifestPermissions(
    context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
  ) {
    val requested = getRequestedManifestPermissions(context)
    val violations = FORBIDDEN_OFFLINE_PERMISSIONS.intersect(requested)
    assertTrue(
      "Merged manifest must not request offline-forbidden permissions, found: $violations (all=$requested)",
      violations.isEmpty(),
    )
  }

  // ---------------------------------------------------------------------------
  // 2. MediaSession / AudioManager / Test Track Lookup Helpers
  // ---------------------------------------------------------------------------

  fun queryAudioDeviceSnapshot(
    context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
  ): AudioDeviceSnapshot {
    val audioManager = context.getSystemService(AudioManager::class.java)
    return AudioDeviceSnapshot(
      isMusicActive = audioManager.isMusicActive,
      musicStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
      musicStreamMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
    )
  }

  fun hasDeclaredMediaSessionService(
    context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
  ): Boolean {
    val pm = context.packageManager
    val intent = Intent(MEDIA3_SESSION_SERVICE_ACTION).setPackage(context.packageName)
    val services =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
      } else {
        @Suppress("DEPRECATION")
        pm.queryIntentServices(intent, 0)
      }
    return services.isNotEmpty()
  }

  /**
   * Queries active platform MediaSessions belonging to DroidAmp.
   * Uses shell permission identity on API 28+ when available and falls back cleanly on SecurityException.
   */
  fun queryAppMediaSessions(
    context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
  ): List<ActiveMediaSessionSnapshot> {
    val manager = context.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    var adopted = false
    val controllers: List<MediaController> =
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
          adopted = true
        }
        manager.getActiveSessions(null)
      } catch (_: SecurityException) {
        emptyList()
      } finally {
        if (adopted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          runCatching { uiAutomation.dropShellPermissionIdentity() }
        }
      }

    return controllers
      .filter { it.packageName == context.packageName }
      .map { controller ->
        val meta = controller.metadata
        val hasArt =
          meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null ||
            meta?.getBitmap(MediaMetadata.METADATA_KEY_ART) != null ||
            !meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).isNullOrBlank() ||
            !meta?.getString(MediaMetadata.METADATA_KEY_ART_URI).isNullOrBlank()
        ActiveMediaSessionSnapshot(
          packageName = controller.packageName,
          playbackState = controller.playbackState?.state,
          title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
          artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
          album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
          hasArtworkBitmapOrUri = hasArt,
        )
      }
  }

  fun findDoorsTestTrackFile(): File? {
    @Suppress("DEPRECATION")
    val publicMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
    val candidates =
      listOf(
        File(DEFAULT_DOORS_SDCARD_PATH),
        File("/storage/emulated/0/Music", DOORS_TRACK_FILE_NAME),
        File(publicMusicDir, DOORS_TRACK_FILE_NAME),
      )
    return candidates.firstOrNull { it.exists() && it.isFile && it.length() > 0L }
  }

  fun findDoorsTestTrackMediaStoreUri(
    context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
  ): Uri? {
    val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
    val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
    val selectionArgs = arrayOf(DOORS_TRACK_FILE_NAME)
    return runCatching {
      context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        null,
      )?.use { cursor ->
        if (cursor.moveToFirst()) {
          val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
          Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
        } else {
          null
        }
      }
    }.getOrNull()
  }

  fun isDoorsTestTrackPresentOnDevice(
    context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
  ): Boolean {
    if (findDoorsTestTrackFile() != null) return true
    if (findDoorsTestTrackMediaStoreUri(context) != null) return true
    return runCatching {
      val pfd =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
          "ls -s \"$DEFAULT_DOORS_SDCARD_PATH\"",
        )
      pfd.use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { r ->
          val out = r.readText()
          out.contains(DOORS_TRACK_FILE_NAME) && !out.contains("No such file", ignoreCase = true)
        }
      }
    }.getOrDefault(false)
  }

  // ---------------------------------------------------------------------------
  // 3. Compose UI Geometry & Zero-In-App-Art Verifiers
  // ---------------------------------------------------------------------------

  /**
   * Verifies EqualizerPanel PREAMP physical isolation:
   * - `PRE` label sits strictly left of `60` label with >= 18dp horizontal gap
   *   (10dp `preampGap` + 18dp dB scale column minus centering margins still leaves > 28dp).
   * - `+12`, `0DB`, `-12` scale labels sit horizontally inside the gap between `PRE` and `60`,
   *   and are ordered vertically top-to-bottom (`+12` -> `0DB` -> `-12`).
   */
  fun assertPreampGapIsolation(composeRule: ComposeTestRule) {
    val preNode = composeRule.onNodeWithText("PRE").assertIsDisplayed().fetchSemanticsNode()
    val band60Node = composeRule.onNodeWithText("60").assertIsDisplayed().fetchSemanticsNode()
    val plus12Node = composeRule.onNodeWithText("+12").assertIsDisplayed().fetchSemanticsNode()
    val zeroDbNode = composeRule.onNodeWithText("0DB").assertIsDisplayed().fetchSemanticsNode()
    val minus12Node = composeRule.onNodeWithText("-12").assertIsDisplayed().fetchSemanticsNode()

    val preBounds = preNode.boundsInRoot
    val band60Bounds = band60Node.boundsInRoot
    val minGapPx = 18f.dpToPx(composeRule.density)
    val actualGapPx = band60Bounds.left - preBounds.right

    assertTrue(
      "Expected PREAMP gap >= ${minGapPx}px between PRE (${preBounds.right}) and 60 (${band60Bounds.left}), got $actualGapPx",
      actualGapPx >= minGapPx,
    )

    listOf("+12" to plus12Node, "0DB" to zeroDbNode, "-12" to minus12Node).forEach { (label, node) ->
      val b = node.boundsInRoot
      assertTrue(
        "Scale label '$label' left (${b.left}) must be >= PRE right (${preBounds.right})",
        b.left >= preBounds.right - 1f,
      )
      assertTrue(
        "Scale label '$label' right (${b.right}) must be <= 60 left (${band60Bounds.left})",
        b.right <= band60Bounds.left + 1f,
      )
    }

    assertTrue(
      "Expected +12 bottom (${plus12Node.boundsInRoot.bottom}) < 0DB top (${zeroDbNode.boundsInRoot.top})",
      plus12Node.boundsInRoot.bottom < zeroDbNode.boundsInRoot.top,
    )
    assertTrue(
      "Expected 0DB bottom (${zeroDbNode.boundsInRoot.bottom}) < -12 top (${minus12Node.boundsInRoot.top})",
      zeroDbNode.boundsInRoot.bottom < minus12Node.boundsInRoot.top,
    )
  }

  /**
   * Verifies PeekingPlaylistSheet non-interactive 12dp swipe buffer above the pull-up handle.
   * Compares the clickable handle panel top edge against the lowest label of EqualizerPanel (`PRE`)
   * or MainDeckPanel (`EQ`) when EQ is collapsed.
   */
  fun assertPeekingSheetSwipeBuffer(composeRule: ComposeTestRule) {
    val peekingTitleNode =
      composeRule
        .onNode(
          hasText("DROIDAMP PLAYLIST ▲") or hasText("DROIDAMP LIBRARY ▲"),
          useUnmergedTree = true,
        )
        .assertIsDisplayed()
        .fetchSemanticsNode()

    val handleNode = findClickableAncestor(peekingTitleNode) ?: peekingTitleNode
    val handleTop = handleNode.boundsInRoot.top

    val eqVisible = composeRule.onAllNodesWithText("PRE").fetchSemanticsNodes().isNotEmpty()
    val upperAnchorNode =
      if (eqVisible) {
        composeRule.onNodeWithText("PRE").fetchSemanticsNode()
      } else {
        composeRule.onNodeWithText("EQ").fetchSemanticsNode()
      }
    val upperBottom = upperAnchorNode.boundsInRoot.bottom
    val minBufferPx = with(composeRule.density) { ClassicDroidAmpSkin.metrics.sheetSwipeBufferHeight.toPx() }
    val panelPaddingPx = with(composeRule.density) { ClassicDroidAmpSkin.metrics.panelPadding.toPx() }
    val requiredSpacingPx = minBufferPx + panelPaddingPx
    val actualSpacingPx = handleTop - upperBottom

    assertTrue(
      "Peeking handle top ($handleTop) must clear upper panel bottom ($upperBottom) by >= ${requiredSpacingPx}px " +
        "(swipeBuffer=$minBufferPx + panelPadding=$panelPaddingPx), got $actualSpacingPx",
      actualSpacingPx >= requiredSpacingPx - 1f,
    )
  }

  /**
   * Verifies gesture-bar / navigationBarsPadding() + 8dp non-interactive bottom lip clearance.
   * Call when either the Peeking sheet or Expanded sheet is rendered.
   */
  fun assertNavigationBarAndBottomLipClearance(composeRule: ComposeTestRule) {
    val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    val activity: Activity? = (composeRule as? AndroidComposeTestRule<*, *>)?.activity
    val navBarBottomPx =
      activity?.window?.decorView?.let { decor ->
        ViewCompat.getRootWindowInsets(decor)
          ?.getInsets(WindowInsetsCompat.Type.navigationBars())
          ?.bottom
      } ?: 0
    val lipHeightPx = with(composeRule.density) { ClassicDroidAmpSkin.metrics.chassisBottomLipHeight.toPx() }
    val maxAllowedContentBottom = rootBounds.bottom - navBarBottomPx - lipHeightPx + 1f

    val isExpanded =
      composeRule
        .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
        .fetchSemanticsNodes()
        .isNotEmpty()

    if (isExpanded) {
      // Bottom-most interactive bar in Expanded sheet is PlaylistMicroTransportBar (`▶` is last transport button)
      val microPlay =
        composeRule
          .onAllNodes(hasText("▶") and hasClickAction())
          .fetchSemanticsNodes()
          .lastOrNull()
          ?: failAndReturnNull("Missing PlaylistMicroTransportBar ▶ button in expanded sheet")
      if (microPlay != null) {
        val bottom = microPlay.boundsInRoot.bottom
        assertTrue(
          "Expanded micro-transport bottom ($bottom) must be <= rootBottom - navBar($navBarBottomPx) - lip($lipHeightPx) ($maxAllowedContentBottom)",
          bottom <= maxAllowedContentBottom,
        )
      }
    } else {
      val peekingTitle =
        composeRule
          .onNode(
            hasText("DROIDAMP PLAYLIST ▲") or hasText("DROIDAMP LIBRARY ▲"),
            useUnmergedTree = true,
          )
          .assertIsDisplayed()
          .fetchSemanticsNode()
      val handleNode =
        findClickableAncestor(peekingTitle)
          ?: run {
            fail("Missing clickable HardwarePanel ancestor for PeekingPlaylistSheet title")
            return
          }
      val handleBounds = handleNode.boundsInRoot
      assertTrue(
        "Peeking sheet bottom (${handleBounds.bottom}) must not exceed rootBounds.bottom (${rootBounds.bottom})",
        handleBounds.bottom <= rootBounds.bottom + 1f,
      )

      val unmergedRoot = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
      val unmergedHandle =
        collectSemanticsNodes(unmergedRoot).firstOrNull { it.id == handleNode.id } ?: handleNode
      val peekingContentNodes =
        collectSemanticsNodes(unmergedHandle).filter { node ->
          if (node.id == unmergedHandle.id || node.id == peekingTitle.id) return@filter false
          val hasContent =
            !node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() ||
              !node.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty()
          val b = node.boundsInRoot
          hasContent &&
            b.top >= peekingTitle.boundsInRoot.bottom - 1f &&
            b.left >= handleBounds.left - 1f &&
            b.right <= handleBounds.right + 1f
        }

      if (peekingContentNodes.isEmpty()) {
        fail(
          "Expected at least one peeking content node inside PeekingPlaylistSheet below title bar " +
            "(handleBounds=$handleBounds, titleBottom=${peekingTitle.boundsInRoot.bottom})",
        )
      }

      peekingContentNodes.forEach { contentNode ->
        val textSummary =
          contentNode.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: contentNode.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: "node#${contentNode.id}"
        assertTrue(
          "Peeking content node '$textSummary' bottom (${contentNode.boundsInRoot.bottom}) must clear " +
            "navBar($navBarBottomPx) + lip($lipHeightPx) ($maxAllowedContentBottom)",
          contentNode.boundsInRoot.bottom <= maxAllowedContentBottom,
        )
      }
    }
  }

  /**
   * Verifies zero in-app album art without false-positives on track title Text or Canvas skin/bevels:
   * - Zero Compose semantics nodes with Role.Image
   * - Zero Compose contentDescription values containing album/artwork/cover tokens
   * - Zero android.widget.ImageView instances in the Activity content view hierarchy
   */
  fun assertZeroInAppAlbumArt(composeRule: ComposeTestRule) {
    val roots = composeRule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
    assertTrue("Expected at least one Compose root semantics node", roots.isNotEmpty())
    val allNodes = roots.flatMap { collectSemanticsNodes(it) }

    allNodes.forEach { node ->
      val role = node.config.getOrNull(SemanticsProperties.Role)
      if (role == Role.Image) {
        val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        fail("Unexpected Role.Image node in DroidAmp UI (zero in-app art invariant): desc=$desc")
      }
      val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
      descriptions.forEach { desc ->
        val lower = desc.lowercase()
        val matched = FORBIDDEN_ART_DESCRIPTION_TOKENS.firstOrNull { token -> lower.contains(token) }
        assertTrue(
          "Unexpected artwork contentDescription '$desc' matched '$matched'",
          matched == null,
        )
      }
    }

    val activity: Activity? = (composeRule as? AndroidComposeTestRule<*, *>)?.activity
    val contentRoot = activity?.findViewById<View>(android.R.id.content)
    if (contentRoot != null) {
      val imageViews = collectImageViews(contentRoot)
      assertTrue(
        "Expected zero ImageView instances in DroidAmp UI, found ${imageViews.size}",
        imageViews.isEmpty(),
      )
    }
  }

  private fun findClickableAncestor(node: SemanticsNode): SemanticsNode? {
    var cur = node.parent
    while (cur != null) {
      if (cur.config.contains(SemanticsActions.OnClick)) return cur
      cur = cur.parent
    }
    return null
  }

  private fun collectSemanticsNodes(root: SemanticsNode): List<SemanticsNode> {
    val result = mutableListOf<SemanticsNode>()
    val stack = ArrayDeque<SemanticsNode>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
      val current = stack.removeLast()
      result.add(current)
      current.children.forEach { stack.addLast(it) }
    }
    return result
  }

  private fun collectImageViews(view: View): List<ImageView> {
    val out = mutableListOf<ImageView>()
    if (view is ImageView) out.add(view)
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        out.addAll(collectImageViews(view.getChildAt(i)))
      }
    }
    return out
  }

  private fun failAndReturnNull(message: String): SemanticsNode? {
    fail(message)
    return null
  }

  private fun Float.dpToPx(density: Density): Float =
    with(density) { Dp(this@dpToPx).toPx() }
}
