package com.wdgts.widgets

import android.content.BroadcastReceiver
import android.content.Context
import kotlin.concurrent.thread

/** Runs [block] off the main thread while keeping the broadcast alive (roughly a 10 s budget). */
fun BroadcastReceiver.async(block: () -> Unit) {
    val pr = goAsync()
    thread(name = "wdgts-async") { try { block() } finally { pr.finish() } }
}

fun refreshKanjiWidgets(c: Context) {
    KanjiWidget.refreshAll(c)
    ExamplesWidget.refreshAll(c)
}
