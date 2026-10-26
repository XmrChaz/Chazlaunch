package com.chazlaunch.owl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Runtime value in the OWL interpreter. */
sealed interface OwlValue {
    data class Str(val s: String) : OwlValue
    data class Num(val n: Double) : OwlValue
    data class Bool(val b: Boolean) : OwlValue
    data class Lst(val items: List<OwlValue>) : OwlValue
    data class Mp(val map: Map<String, OwlValue>) : OwlValue
    data object Null : OwlValue

    fun asText(): String = when (this) {
        is Str -> s
        is Num -> if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
        is Bool -> b.toString()
        is Lst -> items.joinToString(", ") { it.asText() }
        is Mp -> map.entries.joinToString(", ") { "${it.key}=${it.value.asText()}" }
        Null -> "…"
    }

    fun asBool(): Boolean = when (this) {
        is Bool -> b
        is Num -> n != 0.0
        is Str -> s.isNotEmpty()
        is Lst -> items.isNotEmpty()
        Null -> false
        else -> true
    }

    fun asDouble(): Double = when (this) {
        is Num -> n
        is Str -> s.toDoubleOrNull() ?: 0.0
        is Bool -> if (b) 1.0 else 0.0
        else -> 0.0
    }
}

/**
 * Evaluates an [OwlWidget] against reactive state. All reads go through Compose
 * snapshot state ([vars], [nowMillis]) so the renderer recomposes when a source
 * ticks or a handler mutates state.
 *
 * @param apps the launcher's app list, exposed to widgets as an `apps` source.
 * @param onAction dispatches actions (terminal/run/url) to the host.
 */
class OwlEngine(
    val widget: OwlWidget,
    private val apps: List<Map<String, String>>,
    private val onAction: (String) -> Unit,
) {
    val vars = mutableStateMapOf<String, OwlValue>()
    var nowMillis by mutableLongStateOf(System.currentTimeMillis())
        private set

    private val sources: Map<String, OwlDecl.Source> =
        widget.decls.filterIsInstance<OwlDecl.Source>().associateBy { it.name }
    private val lets: Map<String, OwlDecl.Let> =
        widget.decls.filterIsInstance<OwlDecl.Let>().associateBy { it.name }

    init {
        widget.decls.filterIsInstance<OwlDecl.State>().forEach { vars[it.name] = eval(it.initial) }
    }

    /** Smallest cmd/clock cadence, for the renderer's ticker (0 = 1s default). */
    fun minTickMs(): Long {
        val cmds = sources.values.filter { it.everyMs > 0 }.minOfOrNull { it.everyMs }
        val hasClock = sources.values.any { it.kind == OwlDecl.Source.Kind.CLOCK }
        return when {
            hasClock -> 1000L
            cmds != null -> cmds
            else -> 0L
        }
    }

    fun tick() { nowMillis = System.currentTimeMillis() }

    /** Store a cmd source's latest output (called by the renderer's poller). */
    fun setCmdOutput(name: String, output: String) { vars[name] = OwlValue.Str(output.trim()) }

    fun cmdSources(): List<OwlDecl.Source> =
        sources.values.filter { it.kind == OwlDecl.Source.Kind.CMD }

    // --- evaluation ---------------------------------------------------------

    /** @param scope loop-local bindings (e.g. `for app in apps`), checked first. */
    fun eval(expr: OwlExpr, scope: Map<String, OwlValue> = emptyMap()): OwlValue = when (expr) {
        is OwlExpr.Num -> OwlValue.Num(expr.value)
        is OwlExpr.Bool -> OwlValue.Bool(expr.value)
        OwlExpr.Null -> OwlValue.Null
        is OwlExpr.Str -> OwlValue.Str(expr.parts.joinToString("") {
            when (it) {
                is OwlExpr.Str.Part.Lit -> it.text
                is OwlExpr.Str.Part.Interp -> eval(it.expr, scope).asText()
            }
        })
        is OwlExpr.Ref -> resolve(expr.name, scope)
        is OwlExpr.Member -> member(eval(expr.target, scope), expr.field)
        is OwlExpr.Call -> callFn(expr.callee, expr.args, scope)
        is OwlExpr.Binary -> binary(expr.op, expr.left, expr.right, scope)
        is OwlExpr.Assign -> { val v = eval(expr.value, scope); vars[expr.target] = v; v }
    }

    private fun resolve(name: String, scope: Map<String, OwlValue>): OwlValue {
        scope[name]?.let { return it }
        vars[name]?.let { return it }
        lets[name]?.let { return eval(it.expr, scope) }
        sources[name]?.let { src ->
            return when (src.kind) {
                OwlDecl.Source.Kind.CLOCK -> OwlValue.Str(fmt("HH:mm"))
                OwlDecl.Source.Kind.APPS -> OwlValue.Lst(apps.map { a -> OwlValue.Mp(a.mapValues { OwlValue.Str(it.value) }) })
                else -> OwlValue.Null
            }
        }
        return OwlValue.Null
    }

    private fun member(target: OwlValue, field: String): OwlValue = when (target) {
        is OwlValue.Mp -> target.map[field] ?: OwlValue.Null
        is OwlValue.Lst -> if (field == "size") OwlValue.Num(target.items.size.toDouble()) else OwlValue.Null
        else -> clockMember(field)   // e.g. clock.date on a clock-backed value
    }

    private fun clockMember(field: String): OwlValue = when (field) {
        "time" -> OwlValue.Str(fmt("HH:mm"))
        "seconds", "second" -> OwlValue.Str(fmt("ss"))
        "date" -> OwlValue.Str(fmt("EEE d MMM"))
        "hour" -> OwlValue.Str(fmt("HH"))
        "minute" -> OwlValue.Str(fmt("mm"))
        else -> OwlValue.Null
    }

    private fun callFn(name: String, args: List<OwlExpr>, scope: Map<String, OwlValue>): OwlValue {
        val a = args.map { eval(it, scope) }
        return when (name) {
            // actions (used in handlers) — return Null, cause a side effect
            "terminal" -> { onAction("foot -e ${a.firstOrNull()?.asText().orEmpty()}"); OwlValue.Null }
            "run" -> { onAction(a.firstOrNull()?.asText().orEmpty()); OwlValue.Null }
            "url" -> { onAction("xdg-open ${a.firstOrNull()?.asText().orEmpty()}"); OwlValue.Null }
            "notify" -> OwlValue.Null
            // pure helpers
            "upper" -> OwlValue.Str(a.firstOrNull()?.asText().orEmpty().uppercase())
            "lower" -> OwlValue.Str(a.firstOrNull()?.asText().orEmpty().lowercase())
            "len" -> OwlValue.Num(((a.firstOrNull() as? OwlValue.Lst)?.items?.size ?: 0).toDouble())
            else -> OwlValue.Null
        }
    }

    /** Run a handler expression for its side effects (assignments, actions). */
    fun runHandler(expr: OwlExpr, scope: Map<String, OwlValue> = emptyMap()) { eval(expr, scope) }

    private fun binary(op: String, le: OwlExpr, re: OwlExpr, scope: Map<String, OwlValue>): OwlValue {
        if (op == "??") { val l = eval(le, scope); return if (l == OwlValue.Null) eval(re, scope) else l }
        if (op == "&&") return OwlValue.Bool(eval(le, scope).asBool() && eval(re, scope).asBool())
        if (op == "||") return OwlValue.Bool(eval(le, scope).asBool() || eval(re, scope).asBool())
        if (op == "!") return OwlValue.Bool(!eval(le, scope).asBool())
        val l = eval(le, scope); val r = eval(re, scope)
        return when (op) {
            "+" -> if (l is OwlValue.Str || r is OwlValue.Str) OwlValue.Str(l.asText() + r.asText())
                   else OwlValue.Num(l.asDouble() + r.asDouble())
            "-" -> OwlValue.Num(l.asDouble() - r.asDouble())
            "*" -> OwlValue.Num(l.asDouble() * r.asDouble())
            "/" -> OwlValue.Num(if (r.asDouble() == 0.0) 0.0 else l.asDouble() / r.asDouble())
            "==" -> OwlValue.Bool(l.asText() == r.asText())
            "!=" -> OwlValue.Bool(l.asText() != r.asText())
            "<" -> OwlValue.Bool(l.asDouble() < r.asDouble())
            ">" -> OwlValue.Bool(l.asDouble() > r.asDouble())
            "<=" -> OwlValue.Bool(l.asDouble() <= r.asDouble())
            ">=" -> OwlValue.Bool(l.asDouble() >= r.asDouble())
            else -> OwlValue.Null
        }
    }

    private fun fmt(pattern: String): String =
        DateTimeFormatter.ofPattern(pattern)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(nowMillis))
}
