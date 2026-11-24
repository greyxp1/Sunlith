package io.github.sunsind.sunlith.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R

@Composable
fun ScreenTopBar(
    title: String,
    navigationIcon: ImageVector = Icons.AutoMirrored.Outlined.ArrowBack,
    navigationLabel: String = stringResource(R.string.action_back),
    onNavigate: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.background).statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onNavigate) { Icon(navigationIcon, navigationLabel, tint = p.ink) }
        Text(
            title, Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** A rounded surface for grouped content. [modifier] sets the outer spacing. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    emphasis: Boolean = false,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxWidth().background(if (emphasis) p.raised else p.surface, RoundedCornerShape(12.dp)).padding(padding),
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
    SurfaceCard(Modifier.padding(vertical = 8.dp)) {
        Text(text, color = LocalPalette.current.ink, style = MaterialTheme.typography.bodyMedium)
        CardTextButton(action, onAction)
    }
}

/** Muted explanatory text. [live] announces changes, for status that updates in place. */
@Composable
fun Note(text: String, danger: Boolean = false, live: Boolean = false) {
    val p = LocalPalette.current
    Text(
        text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        color = if (danger) p.danger else p.muted, style = MaterialTheme.typography.bodyMedium,
    )
}

/** A boxed status message with an optional action. Changes are announced. */
@Composable
fun Notice(text: String, danger: Boolean = false, muted: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    val p = LocalPalette.current
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().background(p.surface, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (action != null) 4.dp else 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, color = if (danger) p.danger else if (muted) p.muted else p.ink, style = MaterialTheme.typography.bodyMedium)
        if (action != null) CardTextButton(action, onAction)
    }
}

@Composable
private fun RowText(title: String, subtitle: String?, modifier: Modifier, color: Color = LocalPalette.current.ink) {
    Column(modifier) {
        Text(title, color = color, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let { Text(it, color = LocalPalette.current.muted, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun ClickRow(title: String, subtitle: String? = null, enabled: Boolean = true, color: Color? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, subtitle, Modifier.weight(1f), if (enabled) color ?: p.ink else p.faint)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = 12.dp).size(20.dp), tint = p.muted)
    }
}

/** A read-only value beside its title, or below it when [stacked]. */
@Composable
fun ValueRow(title: String, value: String, stacked: Boolean = false) {
    val p = LocalPalette.current
    val modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 10.dp)
    if (stacked) RowText(title, value, modifier)
    else Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = p.ink)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = p.muted, maxLines = 1)
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = switchColors())
    }
}

/** The launcher's calendar-and-sunrise artwork (assets/icon/sunlith-icon.svg), cropped without the adaptive-icon padding. */
@Composable
fun SunMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.sunlith_mark), contentDescription = null, modifier = modifier.size(56.dp))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text.uppercase(locale), modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
        color = p.muted, style = SectionLabel,
    )
}
