package com.droidamp.player.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.droidamp.player.skin.LocalDroidAmpSkin

@Composable
fun HardwareText(
  text: String,
  modifier: Modifier = Modifier,
  style: TextStyle? = null,
  color: Color? = null,
  uppercase: Boolean = true,
  maxLines: Int = 1,
) {
  val skin = LocalDroidAmpSkin.current
  Text(
    text = if (uppercase) text.uppercase() else text,
    style = style ?: skin.typography.microLabel,
    color = color ?: skin.chassis.labelText,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
  )
}

@Composable
fun ScrollingMarqueeText(
  text: String,
  modifier: Modifier = Modifier,
  color: Color? = null,
) {
  val skin = LocalDroidAmpSkin.current
  Text(
    text = text.uppercase(),
    style = skin.typography.vfdInfo,
    color = color ?: skin.vfd.phosphorPrimary,
    maxLines = 1,
    modifier = modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = 36.dpPerSecond()),
  )
}

private fun Int.dpPerSecond() = androidx.compose.ui.unit.Dp(this.toFloat())
