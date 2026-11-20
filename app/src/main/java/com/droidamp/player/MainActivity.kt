package com.droidamp.player

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.LocalDroidAmpSkin
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
        DroidAmpRackScreen(viewModel = rackViewModel)
      }
    }
  }
}
