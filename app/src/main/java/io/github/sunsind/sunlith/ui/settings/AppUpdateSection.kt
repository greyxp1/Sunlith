package io.github.sunsind.sunlith.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.system.APK_MIME
import io.github.sunsind.sunlith.system.AppUpdateState
import io.github.sunsind.sunlith.system.AppUpdateViewModel
import io.github.sunsind.sunlith.system.UpdateCommand
import io.github.sunsind.sunlith.system.UpdateProblem
import io.github.sunsind.sunlith.ui.LocalPalette
import java.io.File

/** The About page's version row. Tapping it checks GitHub for a newer release, and nothing touches the network before that. */
@Composable
fun AppUpdateSection(version: String) {
    val vm = viewModel<AppUpdateViewModel>()
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val command by vm.command.collectAsState()
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.onInstallerReturned() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.onPermissionReturned() }
    DisposableEffect(vm) {
        vm.attach()
        onDispose { vm.detach() }
    }
    LaunchedEffect(command) {
        val next = command ?: return@LaunchedEffect
        if (!vm.consume(next)) return@LaunchedEffect
        try {
            when (next) {
                UpdateCommand.OpenPermission ->
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null)))
                is UpdateCommand.OpenInstaller -> installer.launch(installerIntent(context, next.file))
            }
        } catch (_: ActivityNotFoundException) {
            vm.onLaunchFailed()
        } catch (_: IllegalArgumentException) {
            vm.onLaunchFailed()
        }
    }

    val p = LocalPalette.current
    val shown = state
    val working = shown == AppUpdateState.Checking || shown is AppUpdateState.Preparing
    val status = when (shown) {
        AppUpdateState.Idle -> stringResource(R.string.app_update_check)
        AppUpdateState.Checking -> stringResource(R.string.app_update_checking)
        AppUpdateState.UpToDate -> stringResource(R.string.app_update_up_to_date)
        is AppUpdateState.Available -> stringResource(R.string.app_update_available, shown.version)
        is AppUpdateState.Downloading -> stringResource(R.string.app_update_downloading, shown.version)
        is AppUpdateState.Preparing -> stringResource(R.string.app_update_preparing)
        is AppUpdateState.NeedsPermission -> stringResource(R.string.app_update_permission)
        is AppUpdateState.Ready -> stringResource(R.string.app_update_ready, shown.version)
        is AppUpdateState.Installing -> stringResource(R.string.app_update_installing, shown.version)
        is AppUpdateState.Failed -> stringResource(problemText(shown.problem))
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !working, role = Role.Button, onClick = vm::tap)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_version), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = p.ink)
            Spacer(Modifier.width(12.dp))
            Text(version, style = MaterialTheme.typography.bodyLarge, color = p.muted, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium,
                color = if (shown is AppUpdateState.Failed) p.danger else p.muted)
            if (working) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(16.dp), color = p.muted, strokeWidth = 2.dp)
        }
        if (shown is AppUpdateState.Downloading) {
            val label = stringResource(R.string.app_update_progress)
            LinearProgressIndicator(
                progress = { shown.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = label },
                color = p.accent, trackColor = p.raised,
            )
        }
    }
}

private fun problemText(problem: UpdateProblem) = when (problem) {
    UpdateProblem.NETWORK -> R.string.app_update_error_network
    UpdateProblem.RATE_LIMITED -> R.string.app_update_error_busy
    UpdateProblem.NOT_FOUND -> R.string.app_update_error_not_found
    UpdateProblem.NOT_VERIFIED -> R.string.app_update_error_not_verified
    UpdateProblem.STORAGE -> R.string.app_update_error_storage
    UpdateProblem.NO_INSTALLER -> R.string.app_update_error_installer
}

private fun installerIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // Pin the system installer so another app that claims APKs can't receive the update instead.
    val system = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }.distinct()
    (system.singleOrNull() ?: system.firstOrNull { it == "com.google.android.packageinstaller" || it == "com.android.packageinstaller" })
        ?.let(intent::setPackage)
    return intent
}
