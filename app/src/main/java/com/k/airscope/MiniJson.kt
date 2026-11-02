package com.k.airscope

/** Minimal JSON parser (objects -> Map, arrays -> List, numbers -> Double/Long). No dependencies. */
object MiniJson {
    fun parse(s: String): Any? = P(s).run { ws(); value() }

    private class P(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalArgumentException("eof")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw IllegalArgumentException("bad literal at $i")
            i += w.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                if (s[i] != ':') throw IllegalArgumentException("expected : at $i")
                i++
                m[k] = value(); ws()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IllegalArgumentException("expected , or } at $i")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw IllegalArgumentException("expected , or ] at $i")
                }
            }
        }

        fun str(): String {
            if (s[i] != '"') throw IllegalArgumentException("expected string at $i")
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            if (t.isEmpty()) throw IllegalArgumentException("bad value at $st")
            return if (t.contains('.') || t.contains('e') || t.contains('E')) t.toDouble() else t.toLong()
        }
    }
}
