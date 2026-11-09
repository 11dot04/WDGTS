package com.wdgts.hud.engine.smartspacer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.kieronquinn.app.smartspacer.sdk.client.SmartspacerClient
import com.kieronquinn.app.smartspacer.sdk.client.helper.SmartspacerHelper
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceAction
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceConfig
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceTarget
import com.kieronquinn.app.smartspacer.sdk.model.UiSurface
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.BaseTemplateData
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.CombinedCardsTemplateData
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.TapAction
import com.wdgts.hud.engine.Block
import com.wdgts.hud.engine.TapRef
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The HUD's connection to Smartspacer: one session for the process, the latest targets, the blocks built
 * from them, and firing a tapped action. Lifecycle: [start] when the HUD process comes up (HudHooks.attach,
 * HudWidget.onUpdate), [onScreen] on screen on/off, [stop] on detach.
 */
@SuppressLint("RestrictedApi")
object HudSmartspacerSource {
    enum class Access { UNKNOWN, NOT_INSTALLED, NEEDS_PERMISSION, GRANTED }

    private const val ACTION_MANAGER = "com.kieronquinn.app.smartspacer.MANAGER"
    private const val TARGET_COUNT = 5
    const val REQUEST_PERMISSION = 7101

    @Volatile var targets: List<SmartspaceTarget> = emptyList(); private set
    @Volatile var access: Access = Access.UNKNOWN; private set

    private val mapper = HudSmartspacerMapper(Locale.ENGLISH)
    private var helper: SmartspacerHelper? = null
    private var scope: CoroutineScope? = null
    private var onChange: (() -> Unit)? = null
    private var lastBlocks: List<Block> = emptyList()
    private var firstResult = CompletableDeferred<Unit>()

    fun isInstalled(ctx: Context): Boolean =
        ctx.applicationContext.packageManager.queryIntentServices(Intent(ACTION_MANAGER), 0).isNotEmpty()

    /** Starts the session if Smartspacer is installed. Safe to call repeatedly; the latest [onChange] wins. */
    @Synchronized
    fun start(ctx: Context, onChange: () -> Unit) {
        this.onChange = onChange
        if (helper != null) return
        val app = ctx.applicationContext
        if (!isInstalled(app)) {
            access = Access.NOT_INSTALLED
            return
        }
        val h = SmartspacerHelper(
            SmartspacerClient.getInstance(app),
            SmartspaceConfig(TARGET_COUNT, UiSurface.HOMESCREEN, app.packageName),
        )
        h.setErrorListener(object : SmartspacerHelper.ErrorListener {
            // the session could not be created: almost always because access was not granted yet
            override fun onError() { access = Access.NEEDS_PERMISSION }
        })
        h.onCreate()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        s.launch {
            h.targets.collect { list ->
                access = Access.GRANTED
                targets = list
                firstResult.complete(Unit)
                notifyIfChanged(app)
            }
        }
        helper = h
        scope = s
        if (app.getSystemService(PowerManager::class.java).isInteractive) h.onResume()
    }

    @Synchronized
    fun stop() {
        helper?.onDestroy()
        scope?.cancel()
        helper = null
        scope = null
        targets = emptyList()
        lastBlocks = emptyList()
        firstResult = CompletableDeferred()
        SmartspacerClient.close()
    }

    /** Call after the user granted access so a failed session is rebuilt. */
    fun restart(ctx: Context) {
        val cb = onChange ?: {}
        stop()
        start(ctx, cb)
    }

    fun onScreen(on: Boolean) {
        if (on) helper?.onResume() else helper?.onPause()
    }

    private fun notifyIfChanged(ctx: Context) {
        val b = mapper.blocks(targets, redactSensitive = false)
        if (b != lastBlocks) {
            lastBlocks = b
            onChange?.invoke()
        }
    }

    /** HUD blocks for the current targets; sensitive text is masked while the device is locked. */
    fun blocks(ctx: Context): List<Block> {
        if (targets.isEmpty()) return emptyList()
        val locked = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        return mapper.blocks(targets, redactSensitive = locked)
    }

    // ------------------------------------------------------------------ access

    /** Asks Smartspacer whether this app may read targets. */
    suspend fun checkAccess(ctx: Context): Access {
        val app = ctx.applicationContext
        if (!isInstalled(app)) return Access.NOT_INSTALLED.also { access = it }
        return when (SmartspacerClient.getInstance(app).checkCallingPermission()) {
            true -> Access.GRANTED
            false -> Access.NEEDS_PERMISSION
            null -> Access.UNKNOWN
        }.also { access = it }
    }

    /** Opens Smartspacer's grant screen. Call [restart] from onActivityResult(REQUEST_PERMISSION). */
    fun requestAccess(activity: Activity) {
        CoroutineScope(Dispatchers.Main).launch {
            val sender = SmartspacerClient.getInstance(activity.applicationContext).createPermissionRequestIntentSender()
                ?: return@launch
            activity.startIntentSenderForResult(sender, REQUEST_PERMISSION, null, 0, 0, 0)
        }
    }

    // ------------------------------------------------------------------ taps

    private class Candidate(val id: String?, val pendingIntent: PendingIntent?, val intent: Intent?) {
        val usable get() = pendingIntent != null || intent != null
    }

    private fun candidates(t: SmartspaceTarget): List<Candidate> {
        val out = ArrayList<Candidate>()
        fun act(a: SmartspaceAction?) { if (a != null) out += Candidate(a.id, a.pendingIntent, a.intent) }
        fun tap(a: TapAction?) { if (a != null) out += Candidate(a.id.toString(), a.pendingIntent, a.intent) }
        fun tmpl(d: BaseTemplateData?) {
            if (d == null) return
            listOf(d.primaryItem, d.subtitleItem, d.subtitleSupplementalItem, d.supplementalLineItem, d.supplementalAlarmItem)
                .forEach { tap(it?.tapAction) }
            if (d is CombinedCardsTemplateData) d.combinedCardDataList.forEach { tmpl(it) }
        }
        act(t.baseAction)
        act(t.headerAction)
        t.actionChips.forEach { act(it) }
        t.iconGrid.forEach { act(it) }
        tmpl(t.templateData)
        return out
    }

    private fun launch(ctx: Context, t: SmartspaceTarget, actionId: String?): Boolean {
        val all = candidates(t).filter { it.usable }
        val c = all.firstOrNull { it.id == actionId } ?: all.firstOrNull() ?: return false
        c.pendingIntent?.let { return runCatching { it.send() }.isSuccess }
        c.intent?.let { return runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
        return false
    }

    /**
     * Fires the action behind [tap]. If the process was cold it starts the session and waits briefly for the
     * first targets; if the target is gone it opens Smartspacer instead. [done] runs on the main thread.
     */
    fun fire(ctx: Context, tap: TapRef, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            if (targets.isEmpty()) {
                start(app, onChange ?: {})
                withTimeoutOrNull(2500) { firstResult.await() }
            }
            val t = targets.firstOrNull { it.smartspaceTargetId == tap.targetId }
            val ok = t != null && launch(ctx, t, tap.actionId)
            if (t != null) helper?.onTargetInteraction(t, tap.actionId)
            if (!ok) {
                app.packageManager.queryIntentServices(Intent(ACTION_MANAGER), 0).firstOrNull()?.serviceInfo?.packageName
                    ?.let { pkg -> app.packageManager.getLaunchIntentForPackage(pkg) }
                    ?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            done(ok)
        }
    }
}
