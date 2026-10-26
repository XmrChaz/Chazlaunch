package com.chazlaunch.owl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Renders an [OwlEngine]'s widget tree as Compose, with enough layout controls
 * (screen fill, child weights, alignment, monospace, font sizes, card
 * backgrounds) to build a real desktop. Drives the clock ticker and optionally
 * polls `cmd` sources via [cmdRunner].
 */
@Composable
fun OwlWidgetView(
    engine: OwlEngine,
    modifier: Modifier = Modifier,
    cmdRunner: (suspend (String) -> String)? = null,
) {
    val tick = engine.minTickMs()
    if (tick > 0) LaunchedEffect(engine) { while (isActive) { engine.tick(); delay(tick) } }
    if (cmdRunner != null) LaunchedEffect(engine) {
        coroutineScope {
            engine.cmdSources().forEach { src ->
                launch {
                    while (isActive) {
                        runCatching { engine.setCmdOutput(src.name, cmdRunner(src.arg ?: "")) }
                        delay(if (src.everyMs > 0) src.everyMs else 10_000)
                    }
                }
            }
        }
    }

    val root = engine.widget.root
    Box(modifier) { if (root != null) RenderNode(engine, root, emptyMap(), Modifier) }
}

@Composable
private fun RenderNode(engine: OwlEngine, node: OwlNode, scope: Map<String, OwlValue>, extra: Modifier) {
    when (node) {
        is OwlNode.For -> {
            val items = (engine.eval(node.list, scope) as? OwlValue.Lst)?.items ?: emptyList()
            items.forEach { item ->
                val cs = scope + (node.varName to item)
                node.body.forEach { RenderNode(engine, it, cs, Modifier) }
            }
        }
        is OwlNode.If -> {
            val branch = if (engine.eval(node.cond, scope).asBool()) node.then else node.otherwise
            branch.forEach { RenderNode(engine, it, scope, Modifier) }
        }
        is OwlNode.Element -> RenderElement(engine, node, scope, extra)
    }
}

@Composable
private fun RenderElement(engine: OwlEngine, el: OwlNode.Element, scope: Map<String, OwlValue>, extra: Modifier) {
    var mod = extra.then(el.modifier(engine, scope))
    // Any node with an onTap handler is clickable (cards, rows, boxes …).
    el.handlers["onTap"]?.let { h -> mod = mod.clickable { engine.runHandler(h, scope) } }
    val spacing = (el.prop(engine, "spacing", scope)?.asDouble() ?: 8.0).dp
    when (el.name) {
        "Row" -> Row(
            mod,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = when (el.prop(engine, "align", scope)?.asText()) {
                "top" -> Alignment.Top; "bottom" -> Alignment.Bottom; else -> Alignment.CenterVertically
            },
        ) {
            el.children.forEach { child ->
                val w = childWeightValue(engine, child, scope)
                RenderNode(engine, child, scope, if (w > 0f) Modifier.weight(w) else Modifier)
            }
        }

        "Column", "Grid", "Flow" -> Column(
            mod,
            verticalArrangement = Arrangement.spacedBy(spacing),
            horizontalAlignment = when (el.prop(engine, "align", scope)?.asText()) {
                "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start
            },
        ) {
            el.children.forEach { child ->
                val w = childWeightValue(engine, child, scope)
                RenderNode(engine, child, scope, if (w > 0f) Modifier.weight(w) else Modifier)
            }
        }

        "Box" -> Box(
            mod,
            contentAlignment = when (el.prop(engine, "align", scope)?.asText()) {
                "center" -> Alignment.Center; "bottom" -> Alignment.BottomCenter; "top" -> Alignment.TopCenter
                else -> Alignment.TopStart
            },
        ) { el.children.forEach { RenderNode(engine, it, scope, Modifier) } }

        "Text" -> Text(
            text = el.prop(engine, "text", scope)?.asText() ?: "",
            modifier = mod,
            color = el.colorProp(engine, "color", scope) ?: styleColor(el.prop(engine, "style", scope)?.asText()),
            fontFamily = if (el.prop(engine, "mono", scope)?.asBool() == true) FontFamily.Monospace else null,
            fontSize = el.prop(engine, "fontSize", scope)?.asDouble()?.sp
                ?: styleSize(el.prop(engine, "style", scope)?.asText()),
            fontWeight = if (el.prop(engine, "bold", scope)?.asBool() == true ||
                el.prop(engine, "style", scope)?.asText() == "title") FontWeight.Bold else null,
            lineHeight = el.prop(engine, "fontSize", scope)?.asDouble()?.times(1.3)?.sp
                ?: androidx.compose.ui.unit.TextUnit.Unspecified,
        )

        "Button" -> Box(
            mod.padding(horizontal = 14.dp, vertical = 10.dp),  // onTap already folded into mod
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                el.prop(engine, "text", scope)?.asText() ?: "",
                color = el.colorProp(engine, "color", scope) ?: MaterialTheme.colorScheme.onSurface,
                fontFamily = if (el.prop(engine, "mono", scope)?.asBool() == true) FontFamily.Monospace else null,
            )
        }

        "Progress" -> LinearProgressIndicator(
            progress = { (el.prop(engine, "value", scope)?.asDouble() ?: 0.0).toFloat().coerceIn(0f, 1f) },
            modifier = mod.fillMaxWidth(),
        )

        "Spacer" -> Spacer(mod)

        else -> Column(mod) { el.children.forEach { RenderNode(engine, it, scope, Modifier) } }
    }
}

/** A child's `weight:` prop, applied as a RowScope/ColumnScope weight. */
private fun childWeightValue(engine: OwlEngine, child: OwlNode, scope: Map<String, OwlValue>): Float =
    ((child as? OwlNode.Element)?.props?.get("weight")?.let { engine.eval(it, scope).asDouble() } ?: 0.0).toFloat()

// --- prop helpers -----------------------------------------------------------

private fun OwlNode.Element.prop(engine: OwlEngine, key: String, scope: Map<String, OwlValue>): OwlValue? =
    props[key]?.let { engine.eval(it, scope) }

@Composable
private fun OwlNode.Element.colorProp(engine: OwlEngine, key: String, scope: Map<String, OwlValue>): Color? =
    prop(engine, key, scope)?.asText()?.let { themeColor(it) }

@Composable
private fun OwlNode.Element.modifier(engine: OwlEngine, scope: Map<String, OwlValue>): Modifier {
    var m: Modifier = Modifier
    if (prop(engine, "grow", scope)?.asBool() == true) m = m.fillMaxSize()
    if (prop(engine, "fill", scope)?.asBool() == true) m = m.fillMaxWidth()
    prop(engine, "width", scope)?.let { m = m.width(it.asDouble().dp) }
    prop(engine, "height", scope)?.let { m = m.height(it.asDouble().dp) }
    prop(engine, "size", scope)?.takeIf { it is OwlValue.Num }?.let { m = m.size(it.asDouble().dp) }
    val radius = prop(engine, "radius", scope)?.asDouble()?.dp
    colorProp(engine, "background", scope)?.let { bg ->
        m = if (radius != null) m.clip(RoundedCornerShape(radius)).background(bg) else m.background(bg)
    }
    prop(engine, "padding", scope)?.let { m = m.padding(it.asDouble().dp) }
    return m
}

@Composable
private fun styleColor(style: String?): Color = when (style) {
    "muted" -> MaterialTheme.colorScheme.onSurfaceVariant
    "accent" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onBackground
}

@Composable
private fun styleSize(style: String?) = when (style) {
    "title" -> 30.sp
    "muted" -> 12.sp
    else -> androidx.compose.ui.unit.TextUnit.Unspecified
}

@Composable
private fun themeColor(token: String): Color = when (token) {
    "accent", "primary" -> MaterialTheme.colorScheme.primary
    "accentDim" -> MaterialTheme.colorScheme.secondary
    "foreground" -> MaterialTheme.colorScheme.onBackground
    "muted" -> MaterialTheme.colorScheme.onSurfaceVariant
    "background", "bg" -> MaterialTheme.colorScheme.background
    "surface" -> MaterialTheme.colorScheme.surface
    "surfaceVariant" -> MaterialTheme.colorScheme.surfaceVariant
    "red" -> Color(0xFFE5534B)
    "green" -> Color(0xFF57AB5A)
    "yellow" -> Color(0xFFC69026)
    "blue" -> Color(0xFF539BF5)
    else -> runCatching { Color(android.graphics.Color.parseColor(token)) }.getOrDefault(MaterialTheme.colorScheme.onBackground)
}
