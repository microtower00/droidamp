package com.droidamp.player.audio.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.droidamp.player.domain.model.PlaybackState
import kotlin.math.roundToInt

/**
 * Pure-Kotlin bridge decoupling [Media3AudioEngineController] from Android's [AudioManager]
 * system volume stream and output route callbacks (`ADR-029`), requiring ZERO extra permissions
 * (`ADR-013`).
 */
interface SystemAudioBridge {
  interface OutputRouteListener {
    fun onAudioBecomingNoisy()
    fun onExternalOutputConnected()
  }

  val supportsSystemVolumeSync: Boolean

  fun getStreamVolume0To255(): Int
  fun setStreamVolume0To255(volume0To255: Int)
  fun setVolumeChangeListener(listener: ((Int) -> Unit)?)
  fun setOutputRouteListener(listener: OutputRouteListener?)
  fun release()
}

object NoOpSystemAudioBridge : SystemAudioBridge {
  override val supportsSystemVolumeSync: Boolean = false

  override fun getStreamVolume0To255(): Int = PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME
  override fun setStreamVolume0To255(volume0To255: Int) = Unit
  override fun setVolumeChangeListener(listener: ((Int) -> Unit)?) = Unit
  override fun setOutputRouteListener(listener: SystemAudioBridge.OutputRouteListener?) = Unit
  override fun release() = Unit
}

class AndroidSystemAudioBridge(
  context: Context,
) : SystemAudioBridge {
  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  override val supportsSystemVolumeSync: Boolean = true

  @Volatile
  private var volumeListener: ((Int) -> Unit)? = null

  @Volatile
  private var routeListener: SystemAudioBridge.OutputRouteListener? = null

  @Volatile
  private var initialDevicesRecorded = false

  @Volatile
  private var released = false

  private val broadcastReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(ctx: Context?, intent: Intent?) {
        when (intent?.action) {
          VOLUME_CHANGED_ACTION -> {
            val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            if (stream == AudioManager.STREAM_MUSIC) {
              volumeListener?.invoke(getStreamVolume0To255())
            }
          }
          AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
            routeListener?.onAudioBecomingNoisy()
          }
        }
      }
    }

  private val audioDeviceCallback =
    object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
        if (!initialDevicesRecorded) {
          initialDevicesRecorded = true
          return
        }
        val hasExternalSink = addedDevices.orEmpty().any(::isExternalAudioOutput)
        if (hasExternalSink) {
          routeListener?.onExternalOutputConnected()
        }
      }

      override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
        val hasExternalSink = removedDevices.orEmpty().any(::isExternalAudioOutput)
        if (hasExternalSink) {
          routeListener?.onAudioBecomingNoisy()
        }
      }
    }

  init {
    val filter =
      IntentFilter().apply {
        addAction(VOLUME_CHANGED_ACTION)
        addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
      }
    runCatching {
      ContextCompat.registerReceiver(
        appContext,
        broadcastReceiver,
        filter,
        ContextCompat.RECEIVER_NOT_EXPORTED,
      )
    }
    runCatching {
      audioManager.registerAudioDeviceCallback(
        audioDeviceCallback,
        Handler(Looper.getMainLooper()),
      )
    }
  }

  override fun getStreamVolume0To255(): Int {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
    return ((current.toFloat() / max.toFloat()) * PlaybackState.MAX_VOLUME).roundToInt()
      .coerceIn(PlaybackState.MIN_VOLUME, PlaybackState.MAX_VOLUME)
  }

  override fun setStreamVolume0To255(volume0To255: Int) {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val clamped = volume0To255.coerceIn(PlaybackState.MIN_VOLUME, PlaybackState.MAX_VOLUME)
    val targetStep = ((clamped.toFloat() / PlaybackState.MAX_VOLUME.toFloat()) * max).roundToInt()
      .coerceIn(0, max)
    runCatching {
      val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
      if (currentStep != targetStep) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetStep, 0)
      }
    }
  }

  override fun setVolumeChangeListener(listener: ((Int) -> Unit)?) {
    volumeListener = listener
  }

  override fun setOutputRouteListener(listener: SystemAudioBridge.OutputRouteListener?) {
    routeListener = listener
  }

  override fun release() {
    if (released) return
    released = true
    volumeListener = null
    routeListener = null
    runCatching { appContext.unregisterReceiver(broadcastReceiver) }
    runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
  }

  private fun isExternalAudioOutput(info: AudioDeviceInfo): Boolean {
    if (!info.isSink) return false
    return when (info.type) {
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
      AudioDeviceInfo.TYPE_BLE_HEADSET,
      AudioDeviceInfo.TYPE_BLE_SPEAKER,
      AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
      AudioDeviceInfo.TYPE_WIRED_HEADSET,
      AudioDeviceInfo.TYPE_USB_HEADSET,
      AudioDeviceInfo.TYPE_USB_DEVICE,
      AudioDeviceInfo.TYPE_AUX_LINE,
      -> true
      else -> false
    }
  }

  private companion object {
    const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
  }
}
