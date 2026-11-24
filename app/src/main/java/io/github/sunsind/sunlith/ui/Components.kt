package io.github.sunsind.sunlith.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R

/** A compact bar, or with [scrollBehavior] a larger title that collapses as the page scrolls. */
@Composable
fun ScreenTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val text: @Composable () -> Unit = {
        if (title.isNotEmpty()) Text(title, Modifier.semantics { heading() }, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    // The bar keeps the page's canvas when content scrolls under it, as in Android's settings.
    val canvas = MaterialTheme.colorScheme.canvas
    val colors = TopAppBarDefaults.topAppBarColors(containerColor = canvas, scrolledContainerColor = canvas)
    if (scrollBehavior == null) TopAppBar(title = text, actions = actions, colors = colors)
    else MediumFlexibleTopAppBar(title = text, actions = actions, colors = colors, scrollBehavior = scrollBehavior)
}

/** A settings page whose title collapses into its bar as the content scrolls. */
@Composable
fun PageScaffold(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    canScroll: () -> Boolean = { true },
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(canScroll = canScroll)
    Scaffold(Modifier.nestedScroll(scroll.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.canvas, topBar = { ScreenTopBar(title, scroll, actions) }, content = content)
}

/** The corners of a row inside a group. Rows outside a group stay flat. */
private val LocalGroupShapes = compositionLocalOf<ListItemShapes?> { null }

class ListGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()
    fun item(content: @Composable () -> Unit) { rows += content }
}

/** Rows joined into one rounded block, as in Material's segmented lists. Rows can be added conditionally. */
@Composable
fun ListGroup(content: ListGroupScope.() -> Unit) {
    val rows = ListGroupScope().apply(content).rows
    Column {
        rows.forEachIndexed { index, row -> GroupItem(index, rows.size, row) }
    }
}

/** One row of a group laid out by a lazy list, which knows the row's [index] among [count]. */
@Composable
fun GroupItem(index: Int, count: Int, content: @Composable () -> Unit) {
    Box(
        Modifier.padding(
            start = 16.dp, end = 16.dp,
            top = if (index == 0) 8.dp else ListItemDefaults.SegmentedGap,
            bottom = if (index == count - 1) 8.dp else 0.dp,
        ),
    ) {
        // Material's segmentedShapes(0, 1) falls back to the tight inner corner radius; match the outer group radius instead.
        val shapes = if (count == 1) ListItemDefaults.shapes(shape = MaterialTheme.shapes.large)
        else ListItemDefaults.segmentedShapes(index, count)
        CompositionLocalProvider(LocalGroupShapes provides shapes, content = content)
    }
}

/**
 * A list row that takes its corners from the surrounding group. With [onClick] it reacts to presses, and [modifier]
 * can describe its toggle or selection with [toggleState] or [selectState]. [highlight] tints it, as for unread mail.
 */
@Composable
fun GroupRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    highlight: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shapes = LocalGroupShapes.current
    val container = when {
        highlight -> MaterialTheme.colorScheme.secondaryContainer
        shapes != null -> MaterialTheme.colorScheme.card
        else -> Color.Transparent
    }
    if (onClick != null) SegmentedListItem(
        onClick = onClick, shapes = shapes ?: ListItemDefaults.shapes(shape = RectangleShape), modifier = modifier.fillMaxWidth(),
        enabled = enabled, leadingContent = leading, trailingContent = trailing, supportingContent = supporting,
        colors = ListItemDefaults.segmentedColors(containerColor = container, disabledContainerColor = container),
        content = content,
    ) else ListItem(
        headlineContent = content, modifier = modifier.fillMaxWidth().clip(shapes?.shape ?: RectangleShape),
        leadingContent = leading, trailingContent = trailing, supportingContent = supporting,
        colors = ListItemDefaults.colors(containerColor = container),
    )
}

fun Modifier.toggleState(checked: Boolean, role: Role = Role.Switch) = semantics {
    this.role = role
    toggleableState = ToggleableState(checked)
}

fun Modifier.selectState(selected: Boolean) = semantics {
    role = Role.RadioButton
    this.selected = selected
}

/** A rounded surface for grouped content. [modifier] sets the outer spacing. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    color: Color = MaterialTheme.colorScheme.card,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .background(color, MaterialTheme.shapes.large)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
fun CardTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(text) }
}

@Composable
fun InfoCard(text: String, action: String, onAction: () -> Unit) {
    // Floats over the calendar rather than a settings canvas.
    SurfaceCard(Modifier.padding(vertical = 8.dp), MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        CardTextButton(action, onAction)
    }
}

/** Muted explanatory text. [live] announces changes, for status that updates in place. */
@Composable
fun Note(text: String, danger: Boolean = false, live: Boolean = false) {
    val p = MaterialTheme.colorScheme
    Text(
        text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        color = if (danger) p.error else p.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
    )
}

/** A boxed status message with an optional action. Changes are announced. */
@Composable
fun Notice(text: String, danger: Boolean = false, muted: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .background(if (danger) colors.errorContainer else colors.card, MaterialTheme.shapes.large)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (action != null) 4.dp else 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium,
            color = if (danger) colors.onErrorContainer else if (muted) colors.onSurfaceVariant else colors.onSurface,
        )
        if (action != null) CardTextButton(action, onAction)
    }
}

@Composable
fun ClickRow(
    title: String, subtitle: String? = null, enabled: Boolean = true, color: Color? = null,
    icon: ImageVector? = null, onClick: () -> Unit,
) {
    GroupRow(
        Modifier.semantics { role = Role.Button }, onClick = onClick, enabled = enabled,
        trailing = icon?.let { { Icon(it, null, Modifier.size(20.dp)) } },
        supporting = subtitle?.let { { Text(it) } },
    ) { Text(title, color = color.takeIf { enabled } ?: Color.Unspecified) }
}

/** A read-only value beside its title, or below it when [stacked]. */
@Composable
fun ValueRow(title: String, value: String, stacked: Boolean = false) {
    GroupRow(
        Modifier.semantics(mergeDescendants = true) {},
        trailing = if (stacked) null else { { Text(value, maxLines = 1, style = MaterialTheme.typography.bodyLarge) } },
        supporting = if (stacked) { { Text(value) } } else null,
    ) { Text(title) }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    GroupRow(
        Modifier.toggleState(checked), onClick = { onChange(!checked) }, enabled = enabled,
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        supporting = subtitle?.let { { Text(it) } },
    ) { Text(title) }
}

/** The launcher's calendar-and-sunrise artwork (assets/icon/sunlith-icon.svg), cropped without the adaptive-icon padding. */
@Composable
fun SunMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.sunlith_mark), contentDescription = null, modifier = modifier.size(56.dp))
}

/** A heading above a group. The default [inset] lines it up with the text of the group's rows. */
@Composable
fun SectionTitle(text: String, inset: Dp = 32.dp) {
    Text(
        text, Modifier.padding(start = inset, end = inset, top = 24.dp, bottom = 4.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLargeEmphasized,
    )
}

/** Related fields on one rounded surface, so sets of fields read apart without dividers. */
@Composable
fun FieldGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.card).padding(vertical = 4.dp),
        content = content,
    )
}

/** These insets with [extra] room at the bottom, so a list's last row can scroll clear of the screen's edge. */
@Composable
fun PaddingValues.plusBottom(extra: Dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(calculateStartPadding(direction), calculateTopPadding(), calculateEndPadding(direction), calculateBottomPadding() + extra)
}
