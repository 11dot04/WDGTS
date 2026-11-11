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
    private val slots = listOf("primary", "sub", "c1", "c2", "c3")

    fun get(key: SsIconKey): Bitmap? = cache[key.id]
    fun has(targetId: String, slot: String): Boolean = cache.containsKey("$targetId|$slot")

    /** Loads every icon of [targets] that is not cached yet, and drops icons of targets that are gone. */
    suspend fun preload(ctx: Context, targets: List<SmartspaceTarget>) = withContext(Dispatchers.IO) {
        val live = HashSet<String>()
        for (t in targets) {
            val d = t.templateData ?: continue
            for ((slot, icon) in iconsOf(d)) {
                val key = "${t.smartspaceTargetId}|$slot"
                live += key
                if (cache.containsKey(key)) continue
                load(ctx, icon)?.let { cache[key] = it }
            }
        }
        cache.keys.removeAll { it !in live }
    }

    private fun iconsOf(d: BaseTemplateData): List<Pair<String, Icon>> {
        val items = listOf(
            "primary" to d.primaryItem, "sub" to d.subtitleItem, "c1" to d.subtitleSupplementalItem,
            "c2" to d.supplementalLineItem, "c3" to d.supplementalAlarmItem,
        )
        val out = items.mapNotNull { (slot, item) -> item?.icon?.let { slot to it } }.toMutableList()
        if (d is CombinedCardsTemplateData) d.combinedCardDataList.forEach { out += iconsOf(it).filter { (s, _) -> out.none { o -> o.first == s } } }
        return out
    }

    private fun load(ctx: Context, icon: Icon): Bitmap? {
        if (!icon.isLoadable()) return null
        val d = runCatching { icon.icon.getEnabledDrawableOrNull(ctx) }.getOrNull() ?: return null
        val size = 128
        val raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: size
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: size
        val k = min(size / iw.toFloat(), size / ih.toFloat())
        val w = (iw * k).toInt().coerceAtLeast(1)
        val h = (ih * k).toInt().coerceAtLeast(1)
        d.setBounds((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2)
        d.draw(Canvas(raw))
        val trimmed = GlyphMask.trim(raw) ?: return null
        if (!icon.shouldTint && GlyphMask.coverage(trimmed) > 0.9f) return null
        return trimmed
    }
}
