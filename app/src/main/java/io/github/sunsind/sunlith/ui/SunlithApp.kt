package io.github.sunsind.sunlith.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.SettingsActivity
import io.github.sunsind.sunlith.data.key
import io.github.sunsind.sunlith.ui.calendar.CalendarScreen
import io.github.sunsind.sunlith.ui.calendar.LocalCourseColors
import io.github.sunsind.sunlith.ui.calendar.CourseColors
import io.github.sunsind.sunlith.ui.event.DetailsScreen
import io.github.sunsind.sunlith.ui.event.EventEditorScreen
import io.github.sunsind.sunlith.ui.settings.openAppSettings

@Composable
fun SunlithApp(vm: SunlithViewModel, footer: @Composable () -> Unit = {}) {
    val screenStates = rememberSaveableStateHolder()
    val stackKeys = vm.stack.mapIndexed { index, screen -> screen.savedKey(index + 1) }
    var retainedKeys by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(stackKeys) {
        (retainedKeys - stackKeys.toSet()).forEach(screenStates::removeState)
        retainedKeys = stackKeys
    }
    SunlithWindow(vm, footer) {
        when {
            !vm.aiIntroDone -> ComputerWelcome(vm)
            !vm.hasPermission -> Onboarding(vm)
            vm.needsNotificationStep -> NotificationStep(vm)
            else -> {
                val top = vm.stack.lastOrNull()
                val context = LocalContext.current
                LaunchedEffect(top) { vm.openRequestedComputerSetup() }
                LaunchedEffect(vm.settingsPage) {
                    vm.takeSettingsPage()?.let { SettingsActivity.openRequested(context, it) }
                }
                BackHandler(enabled = top != null || vm.hasDayParent, onBack = vm::back)
                CalendarScreen(vm, if (top != null) Modifier.clearAndSetSemantics { } else Modifier)
                if (top != null) {
                    val surface = if (top == Screen.Search) Modifier else Modifier.background(LocalPalette.current.background)
                    Box(Modifier.fillMaxSize().then(surface).pointerInput(Unit) { }) {
                        screenStates.SaveableStateProvider(stackKeys.last()) {
                            when (top) {
                                Screen.Search -> SearchScreen(vm)
                                Screen.Todo -> TodoScreen(vm)
                                Screen.Editor -> EventEditorScreen(vm)
                                is Screen.Details -> DetailsScreen(vm, top.event)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SunlithWindow(vm: SunlithViewModel, footer: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    SunlithTheme(vm.themeMode) {
        val p = LocalPalette.current
        SystemBarsForPalette()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm) {
            vm.messages.collect { m ->
                val result = snackbar.showSnackbar(
                    m.text, actionLabel = m.actionLabel, withDismissAction = m.actionLabel == null && m.text.length > 60,
                    duration = if (m.action != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) m.action?.invoke()
            }
        }
        val courseColors = remember(vm.courseColorSlots) { CourseColors(vm.courseColorSlots) }
        CompositionLocalProvider(LocalCourseColors provides courseColors) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().background(p.background)) {
                    content()
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 72.dp)) { data ->
                        Snackbar(data, containerColor = p.ink, contentColor = p.background, actionColor = p.background, dismissActionContentColor = p.background, shape = RoundedCornerShape(10.dp))
                    }
                }
                footer()
            }
        }
    }
}

private fun Screen.savedKey(depth: Int): String = "$depth:" + when (this) {
    is Screen.Details -> "details:" + event.key()
    else -> javaClass.name
}

@Composable
private fun ComputerWelcome(vm: SunlithViewModel) {
    OnboardingPage(
        title = stringResource(R.string.ai_intro_title),
        lead = stringResource(R.string.ai_intro_lead),
        primary = stringResource(R.string.action_set_up_ai), onPrimary = vm::startComputerSetup,
        secondary = stringResource(R.string.action_without_ai), onSecondary = vm::finishAiIntro,
    )
}

@Composable
private fun Onboarding(vm: SunlithViewModel) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.READ_CALENDAR] == true && result[Manifest.permission.WRITE_CALENDAR] == true
        if (!granted) {
            denied = true
            permanentlyDenied = activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)
        }
        vm.onCalendarPermissionResult()
    }
    OnboardingPage(
        title = stringResource(R.string.app_name),
        lead = when {
            permanentlyDenied -> stringResource(R.string.onboarding_denied_forever)
            denied -> stringResource(R.string.onboarding_denied)
            else -> stringResource(R.string.onboarding_lead)
        },
        primary = stringResource(if (permanentlyDenied) R.string.action_open_settings else R.string.action_allow_calendar),
        onPrimary = {
            if (permanentlyDenied) context.openAppSettings()
            else launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        },
        secondary = if (permanentlyDenied) stringResource(R.string.action_try_again) else null,
        onSecondary = { launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
    )
}

@Composable
private fun NotificationStep(vm: SunlithViewModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onNotificationPermissionResult(granted, fromOnboarding = true)
    }
    OnboardingPage(
        title = stringResource(R.string.notify_title),
        lead = stringResource(R.string.notify_lead),
        primary = stringResource(R.string.action_allow_notifications),
        onPrimary = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        secondary = stringResource(R.string.action_not_now),
        onSecondary = { vm.finishNotificationStep(enable = false) },
    )
}

/** One mark, one title, one short line, and the actions pinned at the bottom so they stay reachable at any text size. */
@Composable
private fun OnboardingPage(
    title: String, lead: String,
    primary: String, onPrimary: () -> Unit, secondary: String?, onSecondary: () -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SunMark(Modifier.size(72.dp))
                Spacer(Modifier.height(28.dp))
                Text(
                    title, color = p.ink, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 480.dp).semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                Text(lead, color = p.muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 480.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 480.dp).fillMaxWidth()) {
            Button(
                onClick = onPrimary, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
            ) { Text(primary, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
            if (secondary != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(secondary, color = p.muted, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
