package com.chazlaunch.owl

import android.content.Context

/** Loads `.owl` widgets from assets/widgets, with a safe fallback. */
object OwlLoader {

    fun load(context: Context, assetName: String): OwlWidget =
        runCatching {
            context.assets.open("widgets/$assetName").bufferedReader().use {
                OwlParser.parse(it.readText())
            }
        }.getOrNull() ?: fallback(assetName)

    /** A minimal widget shown when a file is missing or fails to parse. */
    private fun fallback(name: String): OwlWidget = OwlWidget(
        kind = "widget",
        name = name,
        header = emptyMap(),
        decls = emptyList(),
        root = OwlNode.Element(
            name = "Text",
            props = mapOf(
                "text" to OwlExpr.Str("widget error: $name", listOf(OwlExpr.Str.Part.Lit("widget error: $name"))),
                "style" to OwlExpr.Str("muted", listOf(OwlExpr.Str.Part.Lit("muted"))),
            ),
            handlers = emptyMap(),
            children = emptyList(),
        ),
    )
}
