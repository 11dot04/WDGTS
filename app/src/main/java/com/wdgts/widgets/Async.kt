package com.wdgts.widgets

import android.content.BroadcastReceiver
import android.content.Context
import com.wdgts.f1.F1Widget
import com.wdgts.music.MusicWidgets
import com.wdgts.sports.TeamWidget
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

fun refreshEverything(c: Context) {
    TithiWidget.refreshAll(c)
    StreaksWidget.refreshAll(c)
    refreshKanjiWidgets(c)
    CountdownBrutalWidget().refreshAll(c)
    CountdownShapeWidget().refreshAll(c)
    MusicWidgets.refreshAll(c)
    TeamWidget().refreshAll(c)
    F1Widget().refreshAll(c)
}
