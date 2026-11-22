package com.droidamp.player

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.storage.KeepScreenOnMode
import com.droidamp.player.ui.rack.DroidAmpRackScreen
import com.droidamp.player.ui.rack.DroidAmpViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )

    val appContainer = DroidAmpAppContainer.getInstance(applicationContext)

    setContent {
      CompositionLocalProvider(LocalDroidAmpSkin provides ClassicDroidAmpSkin) {
        val rackViewModel: DroidAmpViewModel = viewModel(factory = appContainer.viewModelFactory())
        val appPrefs by rackViewModel.appPreferences.collectAsStateWithLifecycle()
        val playbackState by rackViewModel.playbackState.collectAsStateWithLifecycle()

        val shouldKeepScreenOn =
          when (appPrefs.keepScreenOnMode) {
            KeepScreenOnMode.OFF -> false
            KeepScreenOnMode.WHILE_PLAYING -> playbackState.status == PlaybackStatus.PLAYING
            KeepScreenOnMode.ALWAYS -> true
          }

        LaunchedEffect(shouldKeepScreenOn) {
          if (shouldKeepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          }
        }

        DroidAmpRackScreen(viewModel = rackViewModel)
      }
    }
  }
}
