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
import com.wdgts.hud.engine.WeatherInfo
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

    /** An empty list from Smartspacer is often a blip between two real ones; keep the last good list this long. */
    private const val EMPTY_GRACE_MS = 120_000L
    @Volatile private var lastNonEmptyAt = 0L
    @Volatile private var pendingEmpty = false

    private fun live(): List<SmartspaceTarget> =
        if (pendingEmpty && System.currentTimeMillis() - lastNonEmptyAt > EMPTY_GRACE_MS) emptyList() else targets

    fun isInstalled(ctx: Context): Boolean =
        ctx.applicationContext.packageManager.queryIntentServices(Intent(ACTION_MANAGER), 0).isNotEmpty()

    /** Call before reading: restarts the session if the process was killed since the last time. */
    fun ensure(ctx: Context, onChange: () -> Unit) = start(ctx, onChange)

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
                val now = System.currentTimeMillis()
                if (list.isNotEmpty()) {
                    targets = list
                    lastNonEmptyAt = now
                    pendingEmpty = false
                } else if (now - lastNonEmptyAt > EMPTY_GRACE_MS) {
                    targets = emptyList()
                    pendingEmpty = false
                } else {
                    pendingEmpty = true
                }
                firstResult.complete(Unit)
                runCatching { SmartspacerIcons.preload(app, targets) }   // icons first, so the next draw already has them
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
        val b = listOfNotNull(mapper.top(targets, redactSensitive = false))
        val w = mapper.weather(targets)
        val sig = b + listOf(Block("w:" + w?.temp + w?.word, emptyList()))
        if (sig != lastBlocks) {
            lastBlocks = sig
            onChange?.invoke()
        }
    }

    /** The weather Smartspacer carries, if any. */
    fun weather(): WeatherInfo? = mapper.weather(live())

    /** The topmost usable target as a block; sensitive text is masked while the device is locked. */
    fun top(ctx: Context, skip: (Block) -> Boolean = { false }): Block? {
        val ts = live()
        if (ts.isEmpty()) return null
        val locked = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        return mapper.top(ts, redactSensitive = locked, skip = skip)
    }

    private fun slotOf(name: String) = when (name) {
        "primary" -> "primary"; "subtitle" -> "sub"; "subtitleSupplemental" -> "c1"
        "supplementalLine" -> "c2"; else -> "c3"
    }

    /** A readable dump of every live target (copy it from Settings) so the mapping can be checked against real data. */
    fun dump(): String = buildString {
        val ts = targets
        appendLine("Smartspacer targets: ${ts.size}, access=$access")
        ts.forEachIndexed { i, t ->
            appendLine("#$i id=${t.smartspaceTargetId}")
            appendLine("  component=${t.componentName.flattenToShortString()} feature=${t.featureType} score=${t.score}" +
                " dismissable=${t.canBeDismissed} twoComplications=${t.canTakeTwoComplications} hideIfNoComplications=${t.hideIfNoComplications} sensitive=${t.isSensitive}")
            appendLine("  widget=${t.widget != null} remoteViews=${t.remoteViews != null} template=${t.templateData?.javaClass?.simpleName} expiry=${t.expiryTimeMillis}")
            fun act(name: String, a: SmartspaceAction?) {
                if (a == null) return
                val wd = a.weatherData?.let { "${it.state} ${it.temperature}${if (it.useCelsius) "C" else "F"} '${it.description}'" }
                val src = a.subItemInfo?.loggingInfo?.let { "${it.packageName}/${it.featureType}" }
                appendLine("  $name id=${a.id} title='${a.title}' subtitle='${a.subtitle}' icon=${a.icon != null} tap=${a.intent != null || a.pendingIntent != null} weather=$wd source=$src")
            }
            act("header", t.headerAction)
            act("base", t.baseAction)
            t.actionChips.forEach { act("chip", it) }
            t.iconGrid.forEach { act("grid", it) }
            t.templateData?.let { d ->
                listOf(
                    "primary" to d.primaryItem, "subtitle" to d.subtitleItem,
                    "subtitleSupplemental" to d.subtitleSupplementalItem,
                    "supplementalLine" to d.supplementalLineItem, "supplementalAlarm" to d.supplementalAlarmItem,
                ).forEach { (name, item) ->
                    if (item != null) {
                        val ic = item.icon
                        appendLine("  $name text='${item.text?.text}' icon=${ic != null} cached=${SmartspacerIcons.has(t.smartspaceTargetId, slotOf(name))} tint=${ic?.shouldTint} desc='${ic?.contentDescription}' tap=${item.tapAction?.id} source=${item.loggingInfo?.let { "${it.packageName}/${it.featureType}" }}")
                    }
                }
            }
        }
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
