package io.github.sunsind.sunlith.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val COMPUTER_SETUP_ARCHIVE = "sunlith-computer.zip"
private const val PAIRING_SHARE_NAME = "sunlith-pairing.json"

internal fun computerSetupArchive(context: Context, pairing: File): File {
    val archive = File(pairing.parentFile, "${pairing.nameWithoutExtension}.zip")
    ZipOutputStream(archive.outputStream().buffered()).use { zip ->
        zip.putNextEntry(ZipEntry(PAIRING_SHARE_NAME))
        pairing.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
        val assets = listOf("package.json") + context.assets.list("companion/src").orEmpty().map { "src/$it" }
        for (name in assets) {
            zip.putNextEntry(ZipEntry("companion/integrations/mcp/$name"))
            context.assets.open("companion/$name").use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
    return archive
}

internal enum class DesktopClient(val label: String?) { Codex("Codex"), Claude("Claude Code"), Other(null) }

internal fun computerSetupCommand(windows: Boolean, client: DesktopClient): String {
    // Keep the credential file beside the companion checkout so the companion can enforce its private-file check.
    val root = "\$HOME/.sunlith"
    val pairing = "$root/$PAIRING_SHARE_NAME"
    val entry = "$root/companion/integrations/mcp/src/index.js"
    val env = "SUNLITH_DEVICE_CONFIG=$pairing"
    val command = when (client) {
        DesktopClient.Codex -> """codex mcp add sunlith --env "$env" -- node "$entry"""".trim()
        DesktopClient.Claude -> """claude mcp add --scope user --env "$env" --transport stdio sunlith -- node "$entry"""".trim()
        DesktopClient.Other -> """node -e "console.log(JSON.stringify({mcpServers:{sunlith:{command:'node',args:[process.argv[1]],env:{SUNLITH_DEVICE_CONFIG:process.argv[2]}}}},null,2))" "$entry" "$pairing"""".trim()
    }
    return if (windows) {
        """Expand-Archive -LiteralPath "$COMPUTER_SETUP_ARCHIVE" -DestinationPath "$root" -Force -ErrorAction Stop
$command"""
    } else {
        """mkdir -p "$root" && chmod 700 "$root" && unzip -o "$COMPUTER_SETUP_ARCHIVE" -d "$root" && chmod 600 "$pairing" && $command"""
    }
}

internal fun copyComputerText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
internal fun ComputerSetupDialog(onShare: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var windows by rememberSaveable { mutableStateOf(false) }
    var client by rememberSaveable { mutableStateOf(DesktopClient.Codex) }
    val title = stringResource(R.string.connection_setup_command)
    val command = computerSetupCommand(windows, client)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectedChoice(listOf(false, true), windows, { if (it) "Windows" else "macOS / Linux" }) { windows = it }
                ConnectedChoice(DesktopClient.entries, client, { it.label ?: stringResource(R.string.connection_setup_other_client) }) { client = it }
                Text(stringResource(if (windows) R.string.connection_setup_windows else R.string.connection_setup_terminal))
                Text(stringResource(if (client == DesktopClient.Other) R.string.connection_setup_json else R.string.connection_setup_test))
            }
        },
        confirmButton = {
            TextButton({ copyComputerText(context, title, command) }) { Text(stringResource(R.string.connection_setup_copy)) }
        },
        dismissButton = onShare?.let { share ->
            { TextButton(share) { Text(stringResource(R.string.connection_setup_connect_computer)) } }
        },
    )
}

/** One choice among a few, as Material's connected button group that wraps at large text sizes. */
@Composable
private fun <T> ConnectedChoice(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected, onCheckedChange = { onSelect(option) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) { Text(label(option), textAlign = TextAlign.Center) }
        }
    }
}
