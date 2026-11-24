package io.github.sunsind.sunlith

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.Lifecycle
import io.github.sunsind.sunlith.school.SchoolMessagesManager
import io.github.sunsind.sunlith.school.SchoolScheduleManager
import io.github.sunsind.sunlith.ui.settings.CalendarsScreen
import io.github.sunsind.sunlith.device.DeviceLinkScreen
import io.github.sunsind.sunlith.ui.event.EventEditorScreen
import io.github.sunsind.sunlith.ui.PageEntrance
import io.github.sunsind.sunlith.school.SchoolMessagesScreen
import io.github.sunsind.sunlith.school.SchoolPage
import io.github.sunsind.sunlith.school.SchoolScheduleScreen
import io.github.sunsind.sunlith.ui.Screen
import io.github.sunsind.sunlith.ui.settings.SettingsScreen
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.SunlithWindow

/** Each full-page settings entry has a window, so Android owns opening and returning. */
class SettingsActivity : ComponentActivity() {
    internal enum class Page { Settings, Calendars, Computer, History, Diagnostics, School, Alerts, Connection, Messages, Message }

    private val vm: SunlithViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        observeWindowForeground()
        val page = Page.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_PAGE) } ?: Page.Settings
        val importUri = intent.data.takeIf { savedInstanceState?.getBoolean(REVIEW_IMPORT) != false }
        setContent {
            LaunchedEffect(vm.loaded, importUri) {
                if (vm.loaded && importUri != null && vm.importUi == null) vm.startImport(importUri)
            }
            SunlithWindow(vm) {
                BackHandler(onBack = ::finish)
                when (page) {
                    Page.Settings -> SettingsScreen(vm, onBack = ::finish)
                    Page.Calendars -> CalendarsScreen(vm, onBack = ::finish)
                    Page.Computer, Page.History, Page.Diagnostics -> DeviceLinkScreen(
                        vm, onBack = ::finish,
                        page = page, onOpenPage = { open(this, it) },
                    )
                    Page.School, Page.Alerts, Page.Connection -> {
                        val controller = SchoolScheduleManager.get(applicationContext)
                        LaunchedEffect(controller) { controller.onOpen() }
                        SchoolScheduleScreen(
                            controller, onBack = ::finish, onChooseCalendars = ::finish,
                            onOpenMessages = { open(this, Page.Messages) },
                            initialPage = when (page) {
                                Page.Alerts -> SchoolPage.Alerts
                                Page.Connection -> SchoolPage.Connection
                                else -> SchoolPage.Overview
                            },
                            onOpenPage = { open(this, if (it == SchoolPage.Alerts) Page.Alerts else Page.Connection) },
                        )
                    }
                    Page.Messages, Page.Message -> {
                        val state = rememberSaveableStateHolder()
                        if (vm.stack.lastOrNull() == Screen.Editor) {
                            BackHandler(onBack = vm::back)
                            PageEntrance { EventEditorScreen(vm) }
                        } else state.SaveableStateProvider("messages") {
                            SchoolMessagesScreen(
                                SchoolMessagesManager.get(applicationContext), onBack = ::finish,
                                messageId = intent.getStringExtra(EXTRA_MESSAGE),
                                onOpenMessage = { id ->
                                    startActivity(pageIntent(this, Page.Message).putExtra(EXTRA_MESSAGE, id))
                                },
                                onAddSuggestion = vm::addSchoolSuggestion,
                                onOpenDate = { date ->
                                    startActivity(Intent(this, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        .putExtra(MainActivity.EXTRA_SCHOOL_DATE, date.toString()))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(REVIEW_IMPORT, intent.data != null && (!vm.loaded || vm.importUi?.let { it.report == null } == true))
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume(settingsWindow = true)
    }

    companion object {
        private const val EXTRA_MESSAGE = "message_id"
        private const val EXTRA_PAGE = "settings_page"
        private const val REVIEW_IMPORT = "review_import"

        private fun pageIntent(context: Context, page: Page) =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page.name)

        internal fun open(context: Context, page: Page = Page.Settings) {
            val owner = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<ComponentActivity>().firstOrNull()
            if (owner == null || owner.lifecycle.currentState == Lifecycle.State.RESUMED) {
                context.startActivity(pageIntent(context, page))
            }
        }

        internal fun openRequested(context: Context, page: Page) {
            if (page == Page.School || page == Page.Messages) {
                val parents = listOf(Page.Settings, Page.Calendars, Page.School)
                val pages = if (page == Page.Messages) parents + page else parents
                context.startActivities(pages.map { pageIntent(context, it) }.toTypedArray())
            } else open(context, page)
        }

        internal fun openImport(context: Context, uri: Uri) {
            // The sender's grant already applies to our UID. The import can report unreadable files without a second grant.
            context.startActivity(pageIntent(context, Page.Settings).setData(uri))
        }
    }
}
