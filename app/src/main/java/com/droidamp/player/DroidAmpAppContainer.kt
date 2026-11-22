package com.droidamp.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.droidamp.player.audio.service.DspPreferencesStore
import com.droidamp.player.audio.service.Media3AudioEngineController
import com.droidamp.player.domain.contract.AudioEngineController
import com.droidamp.player.domain.contract.AutoEqPresetResolver
import com.droidamp.player.domain.contract.EqfCodec
import com.droidamp.player.domain.contract.MusicSourceRepository
import com.droidamp.player.domain.contract.PlaylistFileCodec
import com.droidamp.player.domain.contract.Q2Codec
import com.droidamp.player.domain.contract.WinampIniCodec
import com.droidamp.player.interop.AutoEqMatcher
import com.droidamp.player.interop.BinaryEqfCodec
import com.droidamp.player.interop.WinampIniParser
import com.droidamp.player.interop.WinampPlaylistCodec
import com.droidamp.player.interop.WinampQ2Codec
import com.droidamp.player.skin.AndroidSkinRepository
import com.droidamp.player.skin.SkinPreferencesStore
import com.droidamp.player.skin.SkinRepository
import com.droidamp.player.skin.StaticSkinRepository
import com.droidamp.player.storage.AndroidAudioMetadataProbe
import com.droidamp.player.storage.AndroidMediaStoreGateway
import com.droidamp.player.storage.AudioMetadataExtractor
import com.droidamp.player.storage.DocumentFileSafTreeGateway
import com.droidamp.player.storage.MediaStoreMusicSourceRepository
import com.droidamp.player.storage.SafMusicSourceRepository
import com.droidamp.player.storage.StoragePreferencesStore
import com.droidamp.player.storage.SwitchingMusicSourceRepository
import com.droidamp.player.ui.rack.AndroidAutoEqCatalogProvider
import com.droidamp.player.ui.rack.AutoEqCatalogProvider
import com.droidamp.player.ui.rack.DroidAmpViewModel
import com.droidamp.player.ui.rack.EmptyAutoEqCatalogProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-scoped dependency container holding singleton Media3/DSP controller,
 * SAF/MediaStore repositories, and Winamp format codecs across Activity recreations.
 */
class DroidAmpAppContainer private constructor(
  appContext: Context,
) {
  val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  val dspPreferencesStore: DspPreferencesStore = DspPreferencesStore.create(appContext)
  val audioEngine: AudioEngineController =
    Media3AudioEngineController.create(
      context = appContext,
      scope = appScope,
      preferencesStore = dspPreferencesStore,
    )

  val storagePreferencesStore: StoragePreferencesStore = StoragePreferencesStore.create(appContext)
  val metadataExtractor: AudioMetadataExtractor =
    AudioMetadataExtractor(AndroidAudioMetadataProbe(appContext))
  val playlistCodec: PlaylistFileCodec = WinampPlaylistCodec()

  val safRepository: SafMusicSourceRepository =
    SafMusicSourceRepository(
      gateway = DocumentFileSafTreeGateway(appContext),
      preferencesStore = storagePreferencesStore,
      metadataExtractor = metadataExtractor,
      playlistCodec = playlistCodec,
      scope = appScope,
    )

  val mediaStoreRepository: MediaStoreMusicSourceRepository =
    MediaStoreMusicSourceRepository(
      gateway = AndroidMediaStoreGateway(appContext),
      metadataExtractor = metadataExtractor,
      playlistCodec = playlistCodec,
    )

  val musicRepository: MusicSourceRepository =
    SwitchingMusicSourceRepository(
      safRepository = safRepository,
      mediaStoreRepository = mediaStoreRepository,
      preferencesStore = storagePreferencesStore,
      scope = appScope,
    )

  val autoEqResolver: AutoEqPresetResolver = AutoEqMatcher()
  val q2Codec: Q2Codec = WinampQ2Codec()
  val eqfCodec: EqfCodec = BinaryEqfCodec()
  val iniCodec: WinampIniCodec = WinampIniParser()
  val autoEqCatalogProvider: AutoEqCatalogProvider =
    AndroidAutoEqCatalogProvider(
      context = appContext,
      q2Codec = q2Codec,
      eqfCodec = eqfCodec,
    )

  val skinPreferencesStore: SkinPreferencesStore = SkinPreferencesStore.create(appContext)
  val skinRepository: SkinRepository =
    AndroidSkinRepository(
      appContext = appContext,
      preferencesStore = skinPreferencesStore,
      scope = appScope,
    )

  fun viewModelFactory(): ViewModelProvider.Factory =
    createViewModelFactory(
      audioEngine = audioEngine,
      musicRepository = musicRepository,
      autoEqResolver = autoEqResolver,
      playlistCodec = playlistCodec,
      autoEqCatalogProvider = autoEqCatalogProvider,
      skinRepository = skinRepository,
      eqfCodec = eqfCodec,
      q2Codec = q2Codec,
      iniCodec = iniCodec,
    )

  companion object {
    @Volatile
    private var instance: DroidAmpAppContainer? = null

    fun getInstance(context: Context): DroidAmpAppContainer =
      instance ?: synchronized(this) {
        instance ?: DroidAmpAppContainer(context.applicationContext).also { instance = it }
      }

    fun createViewModelFactory(
      audioEngine: AudioEngineController,
      musicRepository: MusicSourceRepository,
      autoEqResolver: AutoEqPresetResolver = AutoEqMatcher(),
      playlistCodec: PlaylistFileCodec = WinampPlaylistCodec(),
      autoEqCatalogProvider: AutoEqCatalogProvider = EmptyAutoEqCatalogProvider,
      skinRepository: SkinRepository = StaticSkinRepository(),
      eqfCodec: EqfCodec = BinaryEqfCodec(),
      q2Codec: Q2Codec = WinampQ2Codec(),
      iniCodec: WinampIniCodec = WinampIniParser(),
    ): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          require(modelClass.isAssignableFrom(DroidAmpViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
          }
          return DroidAmpViewModel(
            audioEngine = audioEngine,
            musicRepository = musicRepository,
            autoEqResolver = autoEqResolver,
            playlistCodec = playlistCodec,
            autoEqCatalogProvider = autoEqCatalogProvider,
            skinRepository = skinRepository,
            eqfCodec = eqfCodec,
            q2Codec = q2Codec,
            iniCodec = iniCodec,
          ) as T
        }
      }
  }
}
