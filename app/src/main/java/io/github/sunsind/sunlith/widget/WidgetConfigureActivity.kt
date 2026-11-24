package io.github.sunsind.sunlith.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.settings.SettingsScreen
import io.github.sunsind.sunlith.ui.SystemBarsForPalette
import io.github.sunsind.sunlith.ui.SunlithTheme
import io.github.sunsind.sunlith.ui.SunlithViewModel

class WidgetConfigureActivity : ComponentActivity() {
    private val vm: SunlithViewModel by viewModels()
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        if (provider != ComponentName(this, AgendaWidgetReceiver::class.java)) { finish(); return }
        setContent {
            SunlithTheme(vm.themeMode) {
                val palette = LocalPalette.current
                SystemBarsForPalette()
                Column(Modifier.fillMaxSize().background(palette.background).navigationBarsPadding()) {
                    Box(Modifier.weight(1f)) { SettingsScreen(vm, widget = true, onBack = ::finish) }
                    Button(onClick = ::applyAndFinish, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.action_done))
                    }
                }
            }
        }
    }

    private fun applyAndFinish() {
        AgendaWidgetReceiver.updateAll(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
