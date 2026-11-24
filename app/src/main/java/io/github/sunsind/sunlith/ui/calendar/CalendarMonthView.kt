package io.github.sunsind.sunlith.ui.calendar

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.ui.ViewMode
import io.github.sunsind.sunlith.ui.gridStart
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun MonthBody(context: CalendarContext, modifier: Modifier) {
    val vm = context.vm
    val c = remember(context) { context.forMode(ViewMode.Month) }
    val selected = vm.selectedDateFor(ViewMode.Month)
    val period = c.periodOf(selected)
    var openDate by rememberSaveable { mutableStateOf<Long?>(null) }
    val chooser = rememberChooserHandoff(vm) { openDate = null }
    openDate?.let { epoch ->
        val date = LocalDate.ofEpochDay(epoch)
        val emptyLabel = if (vm.coversRange(date, date, ViewMode.Month)) null else vm.loadError ?: stringResource(R.string.timeline_loading_day)
        EventChooser(rememberDayEvents(vm, ViewMode.Month, date, date)(date), c.f, onOpen = chooser.open,
            onClose = chooser.close, date = date, emptyLabel = emptyLabel, onAdd = { chooser.add(date) })
    }
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val number = with(density) { maxOf(26.dp, typography.bodyMedium.fontSize.toDp() * 1.9f) }
    val chip = with(density) { maxOf(16.dp, typography.labelSmall.lineHeight.toDp() + 4.dp) }
    // The weekday labels belong to each page, so they move with the month while swiping.
    PagedPeriod(c.swipe, period, modifier, userScrollEnabled = vm.viewMode == ViewMode.Month) { absolute ->
        val active = absolute == period && vm.viewMode == ViewMode.Month
        val source = remember(c.viewport, c.viewportScope) { Any() }
        if (active) DisposableEffect(c.viewport, source) { onDispose { c.viewport.release(source) } }
        val onDirectionChanged: (TodayDirection?) -> Unit = remember(c.viewport, c.viewportScope, source, active) {
            { if (active) c.viewport.report(source, it, c.viewportScope) }
        }
        val month = YearMonth.from(selected).plusMonths(absolute - period)
        val date = when {
            absolute == period -> selected
            month == YearMonth.from(c.today) -> c.today
            else -> month.atDay(1)
        }
        val start = gridStart(month)
        val eventsOn = rememberDayEvents(vm, ViewMode.Month, start, start.plusDays(41))
        Column(Modifier.fillMaxSize()) {
            WeekdayLabels(c.f)
            // The rows end above the toolbar, so the last week is never hidden under it.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().padding(bottom = TOOLBAR_CLEARANCE)) {
                val height = maxHeight
                val row = maxOf(height / 6, 56.dp, number + chip + 10.dp)
                FullMonthGrid(
                    month, date, c.today, eventsOn, c.f,
                    onOpenDate = { openDate = it.toEpochDay() },
                    row = row, number = number, chip = chip, viewportHeight = height, textMeasurer = textMeasurer,
                    todayRequest = vm.todayRequestFor(ViewMode.Month), active = active,
                    onTodayDirectionChanged = onDirectionChanged,
                )
            }
        }
    }
}
