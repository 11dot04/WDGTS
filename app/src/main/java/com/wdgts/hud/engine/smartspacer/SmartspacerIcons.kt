package com.wdgts.hud.engine.smartspacer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.kieronquinn.app.smartspacer.sdk.client.utils.getEnabledDrawableOrNull
import com.kieronquinn.app.smartspacer.sdk.client.utils.isLoadable
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceTarget
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.BaseTemplateData
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.CombinedCardsTemplateData
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.Icon
import com.wdgts.widgets.GlyphMask
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Names one icon of one target: [slot] is primary, sub, c1, c2 or c3 (the complications). */
data class SsIconKey(val targetId: String, val slot: String) {
    val id get() = "$targetId|$slot"
}

/**
 * Smartspacer's own icons (the stethoscope, the shoe, the fan, the card ...), turned into clean white
 * silhouettes once per target update, off the main thread, so drawing never has to wait for a content provider.
 * Symbols (tint flag set) are used as they are; colour images are used only when they are real silhouettes
 * with transparency, so logos and QR tiles do not turn into white squares.
 */
@SuppressLint("RestrictedApi")
object SmartspacerIcons {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val sigs = ConcurrentHashMap<String, String>()
    private val status = ConcurrentHashMap<String, String>()

    fun get(key: SsIconKey): Bitmap? = cache[key.id]
    fun has(targetId: String, slot: String): Boolean = cache.containsKey("$targetId|$slot")

    /** Why an icon is or is not drawn, for the "Copy Smartspacer targets" dump. */
    fun status(targetId: String, slot: String): String = status["$targetId|$slot"] ?: "no icon in this slot"

    /**
     * Loads every icon of [targets], and drops icons of targets that are gone. An icon is loaded again when its
     * source changes (a plugin that swaps the fan icon on and off keeps the same target id).
     */
    suspend fun preload(ctx: Context, targets: List<SmartspaceTarget>) = withContext(Dispatchers.IO) {
        val live = HashSet<String>()
        for (t in targets) {
            val d = t.templateData ?: continue
            for ((slot, icon) in iconsOf(d)) {
                val key = "${t.smartspaceTargetId}|$slot"
                live += key
                val sig = "${icon.icon}|${icon.shouldTint}|${icon.contentDescription}"
                if (sigs[key] == sig) continue
                sigs[key] = sig
                val bmp = load(ctx, icon, key)
                if (bmp != null) cache[key] = bmp else cache.remove(key)
            }
        }
        cache.keys.removeAll { it !in live }
        sigs.keys.removeAll { it !in live }
        status.keys.removeAll { it !in live }
    }

    /** Slots are "primary", "sub", "c1".."c3"; icons of the cards of a combined target are prefixed "0:", "1:" ... so they never collide. */
    private fun iconsOf(d: BaseTemplateData, prefix: String = ""): List<Pair<String, Icon>> {
        val items = listOf(
            "primary" to d.primaryItem, "sub" to d.subtitleItem, "c1" to d.subtitleSupplementalItem,
            "c2" to d.supplementalLineItem, "c3" to d.supplementalAlarmItem,
        )
        val out = items.mapNotNull { (slot, item) -> item?.icon?.let { prefix + slot to it } }.toMutableList()
        if (d is CombinedCardsTemplateData) d.combinedCardDataList.forEachIndexed { i, card -> out += iconsOf(card, "$prefix$i:") }
        return out
    }

    private fun load(ctx: Context, icon: Icon, key: String): Bitmap? {
        if (!icon.isLoadable()) return null.also { status[key] = "not loadable (the plugin's icon cannot be resolved)" }
        // getEnabledDrawableOrNull only returns the "disabled state" drawable of a state list (resource icons) and
        // is null for everything else; the SDK's own views fall back to the plain icon, and so must we.
        val d = runCatching { icon.icon.getEnabledDrawableOrNull(ctx) ?: icon.icon.loadDrawable(ctx) }.getOrNull()
            ?: return null.also { status[key] = "drawable failed to load (package not visible, or resource missing)" }
        val size = 128
        val raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: size
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: size
        val k = min(size / iw.toFloat(), size / ih.toFloat())
        val w = (iw * k).toInt().coerceAtLeast(1)
        val h = (ih * k).toInt().coerceAtLeast(1)
        d.setBounds((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2)
        d.draw(Canvas(raw))
        val trimmed = GlyphMask.trim(raw) ?: return null.also { status[key] = "empty image" }
        val cov = GlyphMask.coverage(trimmed)
        if (!icon.shouldTint && cov > 0.9f) return null.also { status[key] = "rejected: opaque colour image (coverage $cov, tint=false)" }
        status[key] = "ok ${trimmed.width}x${trimmed.height} coverage=$cov tint=${icon.shouldTint}"
        return trimmed
    }
}
