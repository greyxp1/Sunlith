package io.github.sunsind.sunlith.ui

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.util.lerp

internal val LocalPageOpening = staticCompositionLocalOf<(View, Offset, () -> Unit) -> Unit> {
    { _, _, open -> open() }
}

@Composable
internal fun rememberPageSource(): PageSource {
    val view = LocalView.current
    val openFrom = LocalPageOpening.current
    return remember(view, openFrom) { PageSource(view, openFrom) }
}

internal class PageSource(private val view: View, private val openFrom: (View, Offset, () -> Unit) -> Unit) {
    private var coordinates: LayoutCoordinates? = null
    val modifier = Modifier.onGloballyPositioned { coordinates = it }

    fun open(tap: Offset? = null, action: () -> Unit) {
        val layout = coordinates?.takeIf { it.isAttached }
        val origin = layout?.let { tap?.let(it::localToRoot) ?: it.boundsInRoot().takeUnless { bounds -> bounds.isEmpty }?.center }
        if (origin == null) action() else openFrom(view, origin, action)
    }
}

@Composable
internal fun PageEntrance(animate: Boolean = true, origin: Offset? = null, content: @Composable () -> Unit) {
    if (!animate) {
        content()
        return
    }
    val background = LocalPalette.current.background
    val progress = remember { Animatable(0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, tween(if (origin == null) 140 else 170)) }
    Box(Modifier.fillMaxSize().graphicsLayer {
        val fraction = progress.value
        alpha = lerp(if (origin == null) 0.65f else 0.78f, 1f, fraction)
        if (origin != null && size.width > 0f && size.height > 0f) {
            transformOrigin = TransformOrigin((origin.x / size.width).coerceIn(0f, 1f), (origin.y / size.height).coerceIn(0f, 1f))
            val scale = lerp(0.96f, 1f, fraction)
            scaleX = scale
            scaleY = scale
        }
    }.background(background)) { content() }
}
