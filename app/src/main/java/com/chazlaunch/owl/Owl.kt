package com.chazlaunch.owl

/**
 * Chazlaunch's implementation of **OWL**, the on-device declarative widget
 * language from OmaLauncher (github.com/SimonSchubert/omalauncher, MIT). This
 * is an independent, compatible subset — enough to drive the launcher's clock
 * and app drawer as editable `.owl` widgets — not a line-for-line port.
 *
 * A `.owl` file is: an optional `meta { owl: N }`, a `widget "name" { … }`
 * block with header props, declarations (`source`/`state`/`let`) and a node
 * tree. See assets/widgets/README.md for the supported surface.
 */

/** Parsed widget: header + declarations + the root node to render. */
data class OwlWidget(
    val kind: String,              // widget | face | app | panel | keybar
    val name: String,
    val header: Map<String, OwlExpr>,
    val decls: List<OwlDecl>,
    val onTap: OwlExpr? = null,    // widget-level tap handler
    val root: OwlNode?,
    val langVersion: Int = 1,
) {
    fun headerInt(key: String, default: Int): Int =
        (header[key] as? OwlExpr.Num)?.value?.toInt() ?: default

    /** size like "3x4" → Pair(cols, rows). */
    fun size(): Pair<Int, Int> {
        val s = (header["size"] as? OwlExpr.Str)?.raw ?: return 3 to 2
        val m = Regex("(\\d+)\\s*x\\s*(\\d+)").find(s) ?: return 3 to 2
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }
}

/** A rendered element: `Name { prop: value; children }`. */
sealed interface OwlNode {
    data class Element(
        val name: String,
        val props: Map<String, OwlExpr>,
        val handlers: Map<String, OwlExpr>,   // onTap, onDrag, …
        val children: List<OwlNode>,
    ) : OwlNode

    /** `for NAME in EXPR { … }` */
    data class For(val varName: String, val list: OwlExpr, val body: List<OwlNode>) : OwlNode

    /** `if COND { … } else { … }` */
    data class If(val cond: OwlExpr, val then: List<OwlNode>, val otherwise: List<OwlNode>) : OwlNode
}

/** Top-level declarations inside a widget. */
sealed interface OwlDecl {
    val name: String

    /** `source x = clock` / `cmd("…") every 5s` / `apps` (built-in). */
    data class Source(
        override val name: String,
        val kind: Kind,
        val arg: String? = null,       // command / url for cmd/http
        val everyMs: Long = 0,
    ) : OwlDecl {
        enum class Kind { CLOCK, CMD, APPS, STATIC }
    }

    /** `state x = expr [persist] [secret]`. */
    data class State(
        override val name: String,
        val initial: OwlExpr,
        val persist: Boolean = false,
    ) : OwlDecl

    /** `let x = expr` — derived, recomputed when inputs change. */
    data class Let(override val name: String, val expr: OwlExpr) : OwlDecl
}

/** Expression AST. Every widget property is one of these (a live expression). */
sealed interface OwlExpr {
    data class Num(val value: Double) : OwlExpr
    data class Str(val raw: String, val parts: List<Part>) : OwlExpr {
        sealed interface Part {
            data class Lit(val text: String) : Part
            data class Interp(val expr: OwlExpr) : Part
        }
    }
    data class Bool(val value: Boolean) : OwlExpr
    data object Null : OwlExpr
    data class Ref(val name: String) : OwlExpr
    data class Member(val target: OwlExpr, val field: String) : OwlExpr
    data class Call(val callee: String, val args: List<OwlExpr>) : OwlExpr
    data class Binary(val op: String, val left: OwlExpr, val right: OwlExpr) : OwlExpr
    data class Assign(val target: String, val value: OwlExpr) : OwlExpr   // for handlers
}
