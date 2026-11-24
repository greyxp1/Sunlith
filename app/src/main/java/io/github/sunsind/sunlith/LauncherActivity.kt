package io.github.sunsind.sunlith

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Keeps the open calendar's task independent of whichever launcher alias is enabled. */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(calendarTaskIntent(this))
        finish()
    }
}


internal fun calendarTaskIntent(context: Context) = Intent(context, MainActivity::class.java)
    .setAction(Intent.ACTION_MAIN)
    .addCategory(Intent.CATEGORY_LAUNCHER)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
