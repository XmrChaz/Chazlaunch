package com.chazlaunch.owl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Renders an [OwlEngine]'s widget tree as Compose. Drives the clock ticker and
 * (optionally) polls `cmd` sources via [cmdRunner].
 */
@Composable
fun OwlWidgetView(
    engine: OwlEngine,
    modifier: Modifier = Modifier,
    cmdRunner: (suspend (String) -> String)? = null,
) {
    val tick = engine.minTickMs()
    if (tick > 0) {
        LaunchedEffect(engine) {
            while (isActive) { engine.tick(); delay(tick) }
        }
    }
    if (cmdRunner != null) {
        LaunchedEffect(engine) {
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
    }

    val root = engine.widget.root
    Box(modifier) {
        if (root != null) RenderNode(engine, root, emptyMap())
    }
}

@Composable
private fun RenderNode(engine: OwlEngine, node: OwlNode, scope: Map<String, OwlValue>) {
    when (node) {
        is OwlNode.For -> {
            val list = engine.eval(node.list, scope)
            val items = (list as? OwlValue.Lst)?.items ?: emptyList()
            items.forEach { item ->
                val childScope = scope + (node.varName to item)
                node.body.forEach { RenderNode(engine, it, childScope) }
            }
        }
        is OwlNode.If -> {
            val branch = if (engine.eval(node.cond, scope).asBool()) node.then else node.otherwise
            branch.forEach { RenderNode(engine, it, scope) }
        }
        is OwlNode.Element -> RenderElement(engine, node, scope)
    }
}

@Composable
private fun RenderElement(engine: OwlEngine, el: OwlNode.Element, scope: Map<String, OwlValue>) {
    val mod = el.modifier(engine, scope)
    when (el.name) {
        "Row" -> Row(
            mod,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { el.children.forEach { RenderNode(engine, it, scope) } }

        "Column", "Grid", "Flow" -> Column(
            mod,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { el.children.forEach { RenderNode(engine, it, scope) } }

        "Box" -> Box(mod) { el.children.forEach { RenderNode(engine, it, scope) } }

        "Text" -> {
            val text = el.prop(engine, "text", scope)?.asText() ?: ""
            val styleName = el.prop(engine, "style", scope)?.asText()
            Text(
                text = text,
                modifier = mod,
                color = el.colorProp(engine, "color", scope) ?: styleColor(styleName),
                style = when (styleName) {
                    "title" -> MaterialTheme.typography.headlineMedium
                    "muted" -> MaterialTheme.typography.bodySmall
                    else -> MaterialTheme.typography.bodyLarge
                },
                fontWeight = if (styleName == "title") FontWeight.Bold else null,
            )
        }

        "Button" -> {
            val text = el.prop(engine, "text", scope)?.asText() ?: ""
            Box(mod.clickable { el.handlers["onTap"]?.let { engine.runHandler(it, scope) } }
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(text, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        "Progress" -> {
            val v = (el.prop(engine, "value", scope)?.asDouble() ?: 0.0).toFloat().coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { v }, modifier = mod.fillMaxWidth())
        }

        "Spacer" -> Spacer(mod.height(8.dp))

        else -> // unknown node (Ring/Sparkline/…): render children so layout survives
            Column(mod) { el.children.forEach { RenderNode(engine, it, scope) } }
    }
}

// --- prop helpers -----------------------------------------------------------

private fun OwlNode.Element.prop(engine: OwlEngine, key: String, scope: Map<String, OwlValue>): OwlValue? =
    props[key]?.let { engine.eval(it, scope) }

@Composable
private fun OwlNode.Element.colorProp(engine: OwlEngine, key: String, scope: Map<String, OwlValue>): Color? =
    prop(engine, key, scope)?.asText()?.let { themeColor(it) }

@Composable
private fun OwlNode.Element.modifier(engine: OwlEngine, scope: Map<String, OwlValue>): Modifier {
    var m: Modifier = Modifier
    prop(engine, "padding", scope)?.let { m = m.padding(it.asDouble().dp) }
    prop(engine, "width", scope)?.let { m = m.width(it.asDouble().dp) }
    prop(engine, "height", scope)?.let { m = m.height(it.asDouble().dp) }
    prop(engine, "size", scope)?.let { m = m.size(it.asDouble().dp) }
    if (prop(engine, "fill", scope)?.asBool() == true) m = m.fillMaxWidth()
    val radius = prop(engine, "radius", scope)?.asDouble()?.dp
    colorProp(engine, "background", scope)?.let { bg ->
        m = if (radius != null) m.clip(RoundedCornerShape(radius)).background(bg) else m.background(bg)
    }
    return m
}

@Composable
private fun styleColor(style: String?): Color = when (style) {
    "muted" -> MaterialTheme.colorScheme.onSurfaceVariant
    "accent" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onBackground
}

@Composable
private fun themeColor(token: String): Color = when (token) {
    "accent", "primary" -> MaterialTheme.colorScheme.primary
    "foreground" -> MaterialTheme.colorScheme.onBackground
    "muted" -> MaterialTheme.colorScheme.onSurfaceVariant
    "background" -> MaterialTheme.colorScheme.background
    "surface" -> MaterialTheme.colorScheme.surface
    "red" -> Color(0xFFE5534B)
    "green" -> Color(0xFF57AB5A)
    "yellow" -> Color(0xFFC69026)
    else -> MaterialTheme.colorScheme.onBackground
}
