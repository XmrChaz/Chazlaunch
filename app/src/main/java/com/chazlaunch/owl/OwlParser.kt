package com.chazlaunch.owl

import android.util.Log

/**
 * Tolerant recursive-descent parser for the OWL subset (see Owl.kt). It never
 * throws into the UI: on an unexpected token it logs and recovers so a bad
 * widget degrades instead of crashing the launcher.
 */
object OwlParser {
    private const val TAG = "OwlParser"

    fun parse(text: String): OwlWidget? = try {
        Parser(Lexer(stripComments(text)).lex()).parseWidget()
    } catch (t: Throwable) {
        Log.w(TAG, "parse failed: ${t.message}")
        null
    }

    /** Strip `# line` and `// line` comments (outside strings). */
    private fun stripComments(src: String): String = buildString {
        var i = 0
        var inStr = false
        while (i < src.length) {
            val c = src[i]
            if (inStr) {
                append(c); if (c == '"') inStr = false; i++; continue
            }
            when {
                c == '"' -> { inStr = true; append(c); i++ }
                c == '#' || (c == '/' && i + 1 < src.length && src[i + 1] == '/') -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                else -> { append(c); i++ }
            }
        }
    }

    // --- Lexer --------------------------------------------------------------

    private enum class T { IDENT, NUM, STR, SYM, EOF }
    private data class Tok(val type: T, val text: String)

    private class Lexer(val src: String) {
        var i = 0
        fun lex(): List<Tok> {
            val out = ArrayList<Tok>()
            while (i < src.length) {
                val c = src[i]
                when {
                    c.isWhitespace() -> i++
                    c == '"' -> out.add(Tok(T.STR, readString()))
                    c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) ->
                        out.add(Tok(T.NUM, readNumber()))
                    c.isLetter() || c == '_' -> out.add(Tok(T.IDENT, readIdent()))
                    else -> out.add(Tok(T.SYM, readSymbol()))
                }
            }
            out.add(Tok(T.EOF, ""))
            return out
        }

        private fun readString(): String {
            val sb = StringBuilder()
            i++ // opening quote
            while (i < src.length && src[i] != '"') {
                if (src[i] == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2 }
                else sb.append(src[i++])
            }
            i++ // closing quote
            return sb.toString()
        }

        private fun readNumber(): String {
            val start = i
            while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
            return src.substring(start, i)
        }

        private fun readIdent(): String {
            val start = i
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
            return src.substring(start, i)
        }

        private val twoCharOps = setOf("==", "!=", "<=", ">=", "&&", "||", "??")
        private fun readSymbol(): String {
            if (i + 1 < src.length) {
                val two = src.substring(i, i + 2)
                if (two in twoCharOps) { i += 2; return two }
            }
            return src[i++].toString()
        }
    }

    // --- Parser -------------------------------------------------------------

    private class Parser(val toks: List<Tok>) {
        var p = 0
        fun peek() = toks[p]
        fun next() = toks[p++]
        fun atEnd() = peek().type == T.EOF
        fun isSym(s: String) = peek().type == T.SYM && peek().text == s
        fun isIdent(s: String) = peek().type == T.IDENT && peek().text == s
        fun eatSym(s: String) { if (isSym(s)) p++ }
        fun expectSym(s: String) { if (!isSym(s)) error("expected '$s' got '${peek().text}'"); p++ }

        fun parseWidget(): OwlWidget {
            var lang = 1
            if (isIdent("meta")) { next(); expectSym("{"); lang = parseMeta(); }
            val kind = if (peek().type == T.IDENT) next().text else "widget"
            val name = if (peek().type == T.STR) next().text else ""
            expectSym("{")

            val header = LinkedHashMap<String, OwlExpr>()
            val decls = ArrayList<OwlDecl>()
            val nodes = ArrayList<OwlNode>()
            var widgetTap: OwlExpr? = null

            while (!isSym("}") && !atEnd()) {
                when {
                    isIdent("source") -> { next(); decls.add(parseSource()) }
                    isIdent("state") -> { next(); decls.add(parseState()) }
                    isIdent("let") -> { next(); decls.add(parseLet()) }
                    isIdent("for") || isIdent("if") -> nodes.add(parseNode())
                    // IDENT ':' -> header prop or widget handler; IDENT '{' -> node
                    peek().type == T.IDENT && toks[p + 1].let { it.type == T.SYM && it.text == ":" } -> {
                        val key = next().text; expectSym(":")
                        val v = if (key.startsWith("on")) parseAction() else parseExpr()
                        eatSym(";")
                        if (key == "onTap") widgetTap = v else header[key] = v
                    }
                    peek().type == T.IDENT -> nodes.add(parseNode())
                    else -> next() // skip stray token
                }
            }
            expectSym("}")
            val root = when (nodes.size) {
                0 -> null
                1 -> nodes[0]
                else -> OwlNode.Element("Column", emptyMap(), emptyMap(), nodes)
            }
            return OwlWidget(kind, name, header, decls, widgetTap, root, lang)
        }

        private fun parseMeta(): Int {
            var v = 1
            while (!isSym("}") && !atEnd()) {
                if (isIdent("owl")) { next(); expectSym(":"); v = (next().text.toDoubleOrNull() ?: 1.0).toInt() }
                else next()
            }
            expectSym("}")
            return v
        }

        private fun parseSource(): OwlDecl.Source {
            val name = next().text
            expectSym("=")
            return when {
                isIdent("clock") -> { next(); OwlDecl.Source(name, OwlDecl.Source.Kind.CLOCK) }
                isIdent("apps") -> { next(); OwlDecl.Source(name, OwlDecl.Source.Kind.APPS) }
                isIdent("cmd") -> {
                    next(); expectSym("("); val arg = if (peek().type == T.STR) next().text else ""; expectSym(")")
                    var every = 0L
                    if (isIdent("every")) { next(); every = parseDuration() }
                    OwlDecl.Source(name, OwlDecl.Source.Kind.CMD, arg, every)
                }
                else -> { parseExpr(); OwlDecl.Source(name, OwlDecl.Source.Kind.STATIC) }
            }
        }

        private fun parseDuration(): Long {
            val n = next().text          // number, possibly with unit suffix glued or separate
            val num = Regex("[0-9.]+").find(n)?.value?.toDoubleOrNull() ?: 0.0
            var unit = Regex("[a-zA-Z]+").find(n)?.value ?: ""
            if (unit.isEmpty() && peek().type == T.IDENT) unit = next().text
            return when (unit) {
                "ms" -> num.toLong()
                "s", "" -> (num * 1000).toLong()
                "m" -> (num * 60_000).toLong()
                "h" -> (num * 3_600_000).toLong()
                else -> (num * 1000).toLong()
            }
        }

        private fun parseState(): OwlDecl.State {
            val name = next().text; expectSym("=")
            val init = parseExpr()
            var persist = false
            while (isIdent("persist") || isIdent("secret")) { if (isIdent("persist")) persist = true; next() }
            eatSym(";")
            return OwlDecl.State(name, init, persist)
        }

        private fun parseLet(): OwlDecl.Let {
            val name = next().text; expectSym("=")
            val e = parseExpr(); eatSym(";")
            return OwlDecl.Let(name, e)
        }

        private fun parseNode(): OwlNode {
            if (isIdent("for")) {
                next(); val v = next().text
                if (isIdent("in")) next()
                val list = parseExpr()
                expectSym("{"); val body = parseNodeList(); expectSym("}")
                return OwlNode.For(v, list, body)
            }
            if (isIdent("if")) {
                next(); val cond = parseExpr()
                expectSym("{"); val then = parseNodeList(); expectSym("}")
                var other = emptyList<OwlNode>()
                if (isIdent("else")) { next(); expectSym("{"); other = parseNodeList(); expectSym("}") }
                return OwlNode.If(cond, then, other)
            }
            val name = next().text
            expectSym("{")
            val props = LinkedHashMap<String, OwlExpr>()
            val handlers = LinkedHashMap<String, OwlExpr>()
            val children = ArrayList<OwlNode>()
            while (!isSym("}") && !atEnd()) {
                when {
                    isIdent("for") || isIdent("if") -> children.add(parseNode())
                    isIdent("let") -> { next(); parseLet() /* node-local let: ignored in subset */ }
                    peek().type == T.IDENT && toks[p + 1].let { it.type == T.SYM && it.text == ":" } -> {
                        val key = next().text; expectSym(":")
                        if (key.startsWith("on")) handlers[key] = parseAction() else props[key] = parseExpr()
                        eatSym(";")
                    }
                    peek().type == T.IDENT -> children.add(parseNode())
                    else -> next()
                }
            }
            expectSym("}")
            return OwlNode.Element(name, props, handlers, children)
        }

        private fun parseNodeList(): List<OwlNode> {
            val out = ArrayList<OwlNode>()
            while (!isSym("}") && !atEnd()) {
                if (peek().type == T.IDENT) out.add(parseNode()) else next()
            }
            return out
        }

        /** A handler value: an assignment `x = expr` or an action expression. */
        private fun parseAction(): OwlExpr {
            if (peek().type == T.IDENT && toks[p + 1].let { it.type == T.SYM && it.text == "=" }) {
                val target = next().text; expectSym("=")
                return OwlExpr.Assign(target, parseExpr())
            }
            return parseExpr()
        }

        // expression precedence ladder
        fun parseExpr(): OwlExpr = parseCoalesce()
        private fun parseCoalesce(): OwlExpr {
            var l = parseOr()
            while (isSym("??")) { next(); l = OwlExpr.Binary("??", l, parseOr()) }
            return l
        }
        private fun parseOr(): OwlExpr {
            var l = parseAnd(); while (isSym("||")) { next(); l = OwlExpr.Binary("||", l, parseAnd()) }; return l
        }
        private fun parseAnd(): OwlExpr {
            var l = parseEq(); while (isSym("&&")) { next(); l = OwlExpr.Binary("&&", l, parseEq()) }; return l
        }
        private fun parseEq(): OwlExpr {
            var l = parseCmp()
            while (isSym("==") || isSym("!=")) { val op = next().text; l = OwlExpr.Binary(op, l, parseCmp()) }
            return l
        }
        private fun parseCmp(): OwlExpr {
            var l = parseAdd()
            while (isSym("<") || isSym(">") || isSym("<=") || isSym(">=")) {
                val op = next().text; l = OwlExpr.Binary(op, l, parseAdd())
            }
            return l
        }
        private fun parseAdd(): OwlExpr {
            var l = parseMul()
            while (isSym("+") || isSym("-")) { val op = next().text; l = OwlExpr.Binary(op, l, parseMul()) }
            return l
        }
        private fun parseMul(): OwlExpr {
            var l = parseUnary()
            while (isSym("*") || isSym("/")) { val op = next().text; l = OwlExpr.Binary(op, l, parseUnary()) }
            return l
        }
        private fun parseUnary(): OwlExpr {
            if (isSym("-")) { next(); return OwlExpr.Binary("-", OwlExpr.Num(0.0), parseUnary()) }
            if (isSym("!")) { next(); return OwlExpr.Binary("!", parseUnary(), OwlExpr.Null) }
            return parsePrimary()
        }
        private fun parsePrimary(): OwlExpr {
            val t = peek()
            when (t.type) {
                T.NUM -> { next(); return OwlExpr.Num(t.text.toDoubleOrNull() ?: 0.0) }
                T.STR -> { next(); return buildStringExpr(t.text) }
                T.IDENT -> {
                    next()
                    when (t.text) {
                        "true" -> return OwlExpr.Bool(true)
                        "false" -> return OwlExpr.Bool(false)
                        "null" -> return OwlExpr.Null
                    }
                    var e: OwlExpr = if (isSym("(")) {
                        next(); val args = parseArgs(); expectSym(")"); OwlExpr.Call(t.text, args)
                    } else OwlExpr.Ref(t.text)
                    while (isSym(".")) { next(); e = OwlExpr.Member(e, next().text) }
                    return e
                }
                T.SYM -> if (t.text == "(") { next(); val e = parseExpr(); expectSym(")"); return e }
                else -> {}
            }
            next(); return OwlExpr.Null
        }
        private fun parseArgs(): List<OwlExpr> {
            val out = ArrayList<OwlExpr>()
            if (isSym(")")) return out
            out.add(parseExpr())
            while (isSym(",")) { next(); out.add(parseExpr()) }
            return out
        }

        /** Split a raw string on ${…} into literal + interpolated parts. */
        private fun buildStringExpr(raw: String): OwlExpr.Str {
            val parts = ArrayList<OwlExpr.Str.Part>()
            var i = 0
            val lit = StringBuilder()
            while (i < raw.length) {
                if (raw[i] == '$' && i + 1 < raw.length && raw[i + 1] == '{') {
                    if (lit.isNotEmpty()) { parts.add(OwlExpr.Str.Part.Lit(lit.toString())); lit.clear() }
                    val end = raw.indexOf('}', i + 2)
                    if (end < 0) { lit.append(raw.substring(i)); break }
                    val inner = raw.substring(i + 2, end)
                    val e = try { Parser(Lexer(inner).lex()).parseExpr() } catch (_: Throwable) { OwlExpr.Null }
                    parts.add(OwlExpr.Str.Part.Interp(e))
                    i = end + 1
                } else lit.append(raw[i++])
            }
            if (lit.isNotEmpty()) parts.add(OwlExpr.Str.Part.Lit(lit.toString()))
            return OwlExpr.Str(raw, parts)
        }
    }
}
