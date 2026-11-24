package io.github.sunsind.sunlith.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
    val motion = MaterialTheme.motionScheme
    // Both pages move like Android's activity transition, and only the one on top fades.
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val distance = (96 * direction).dp
    val pixels = with(LocalDensity.current) { distance.roundToPx() }
    val transition = updateTransition(page, label = "Page")
    Box(Modifier.fillMaxSize().clipToBounds()) {
        if (background != null) {
            val offset by transition.animateDp(
                transitionSpec = { if (animated(initialState) || animated(targetState)) motion.defaultSpatialSpec() else snap() },
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
                val moves = if (opening) animated(targetState) else animated(initialState)
                val enter = if (moves) {
                    if (opening) slideInHorizontally(motion.defaultSpatialSpec()) { pixels } + fadeIn(motion.fastEffectsSpec())
                    else slideInHorizontally(motion.defaultSpatialSpec()) { -pixels }
                } else EnterTransition.None
                val exit = if (moves) {
                    if (opening) slideOutHorizontally(motion.defaultSpatialSpec()) { -pixels }
                    else slideOutHorizontally(motion.defaultSpatialSpec()) { pixels } + fadeOut(motion.fastEffectsSpec())
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
