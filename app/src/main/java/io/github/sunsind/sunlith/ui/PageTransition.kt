package io.github.sunsind.sunlith.ui

import android.os.Build
import android.view.animation.AnimationUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
internal fun <T> PageTransition(
    page: T,
    depth: (T) -> Int,
    animated: (T) -> Boolean = { true },
    key: (T) -> Any? = { it },
    background: (@Composable (Modifier) -> Unit)? = null,
    content: @Composable (T, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val easing = remember(context) {
        if (Build.VERSION.SDK_INT >= 28) {
            Easing(AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_extra_slow_in)::getInterpolation)
        } else FastOutSlowInEasing
    }
    // Android's activity transition moves both windows and fades only the one on top.
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val distance = (96 * direction).dp
    val pixels = with(LocalDensity.current) { distance.roundToPx() }
    val transition = updateTransition(page, label = "Page")
    Box(Modifier.fillMaxSize().clipToBounds()) {
        if (background != null) {
            val offset by transition.animateDp(
                transitionSpec = { if (animated(initialState) || animated(targetState)) tween(450, easing = easing) else snap() },
                label = "Calendar",
            ) { if (animated(it)) -distance else 0.dp }
            background(Modifier.graphicsLayer { translationX = offset.toPx() })
        }
        val coversBackground = depth(page) > 0 || depth(transition.currentState) > 0
        transition.AnimatedContent(
            modifier = Modifier.fillMaxSize().then(if (coversBackground) Modifier.pointerInput(Unit) { } else Modifier),
            contentKey = key,
            transitionSpec = {
                val opening = depth(targetState) >= depth(initialState)
                val motion = if (opening) animated(targetState) else animated(initialState)
                val enter = if (motion) {
                    if (opening) slideInHorizontally(tween(450, easing = easing)) { pixels } +
                        fadeIn(tween(83, delayMillis = 50, easing = LinearEasing))
                    else slideInHorizontally(tween(450, easing = easing)) { -pixels }
                } else EnterTransition.None
                val exit = if (motion) {
                    if (opening) slideOutHorizontally(tween(450, easing = easing)) { -pixels }
                    else slideOutHorizontally(tween(450, easing = easing)) { pixels } +
                        fadeOut(tween(83, delayMillis = 35, easing = LinearEasing))
                } else ExitTransition.None
                (enter togetherWith exit).apply {
                    targetContentZIndex = depth(targetState).toFloat()
                }.using(null)
            },
        ) { screen ->
            val active = screen == page
            Box(Modifier.fillMaxSize().then(if (active) Modifier else Modifier.clearAndSetSemantics { })) {
                content(screen, active)
                if (!active && (depth(screen) > 0 || background == null)) Box(Modifier.fillMaxSize().pointerInput(Unit) { })
            }
        }
    }
}
