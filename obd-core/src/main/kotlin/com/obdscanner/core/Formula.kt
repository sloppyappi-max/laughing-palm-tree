package com.obdscanner.core

/**
 * A small formula language for custom sensors, in the style used by other OBD apps:
 *
 * - `A`…`H` are the response data bytes (0–255), `A` being the first byte after the echo.
 * - `+ - * /`, parentheses, decimal numbers.
 * - `SIGNED(A)` treats one byte as -128..127; `SIGNED16(A,B)` treats two bytes as a signed 16-bit value.
 * - `BIT(A,3)` is bit 3 (0 or 1) of byte A; `MIN(x,y)`, `MAX(x,y)`, `ABS(x)`.
 *
 * Examples: `A-40`, `(A*256+B)/4`, `SIGNED16(A,B)/100`.
 */
class Formula private constructor(private val root: Node, val text: String) {

    fun evaluate(bytes: IntArray): Double = root.eval(bytes)

    /** Number of data bytes the formula reads (highest variable used). */
    val bytesNeeded: Int get() = root.maxByte() + 1

    override fun toString() = text

    companion object {
        /** Parses [text], throwing [IllegalArgumentException] with a readable message on errors. */
        fun parse(text: String): Formula {
            val parser = Parser(text)
            val node = parser.expression()
            parser.expectEnd()
            return Formula(node, text.trim())
        }

        fun parseOrNull(text: String): Formula? = runCatching { parse(text) }.getOrNull()
    }

    private sealed interface Node {
        fun eval(b: IntArray): Double
        fun maxByte(): Int = -1
    }

    private class Num(val v: Double) : Node {
        override fun eval(b: IntArray) = v
    }

    private class Var(val index: Int) : Node {
        override fun eval(b: IntArray): Double {
            require(index < b.size) { "Response has no byte ${'A' + index}" }
            return b[index].toDouble()
        }
        override fun maxByte() = index
    }

    private class Neg(val n: Node) : Node {
        override fun eval(b: IntArray) = -n.eval(b)
        override fun maxByte() = n.maxByte()
    }

    private class Bin(val op: Char, val l: Node, val r: Node) : Node {
        override fun eval(b: IntArray): Double {
            val x = l.eval(b)
            val y = r.eval(b)
            return when (op) {
                '+' -> x + y
                '-' -> x - y
                '*' -> x * y
                '/' -> if (y == 0.0) Double.NaN else x / y
                else -> error("bad operator $op")
            }
        }
        override fun maxByte() = maxOf(l.maxByte(), r.maxByte())
    }

    private class Call(val name: String, val args: List<Node>) : Node {
        override fun eval(b: IntArray): Double {
            val v = args.map { it.eval(b) }
            return when (name) {
                "SIGNED" -> v[0].toInt().let { if (it >= 128) it - 256 else it }.toDouble()
                "SIGNED16" -> (v[0].toInt() * 256 + v[1].toInt()).let { if (it >= 0x8000) it - 0x10000 else it }.toDouble()
                "BIT" -> ((v[0].toInt() shr v[1].toInt()) and 1).toDouble()
                "MIN" -> minOf(v[0], v[1])
                "MAX" -> maxOf(v[0], v[1])
                "ABS" -> kotlin.math.abs(v[0])
                else -> error("unknown function $name")
            }
        }
        override fun maxByte() = args.maxOf { it.maxByte() }
    }

    private class Parser(private val s: String) {
        private var i = 0

        private val arity = mapOf("SIGNED" to 1, "SIGNED16" to 2, "BIT" to 2, "MIN" to 2, "MAX" to 2, "ABS" to 1)

        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char? { skip(); return s.getOrNull(i) }

        fun expectEnd() {
            if (peek() != null) throw IllegalArgumentException("Unexpected '${s[i]}' at position ${i + 1}")
        }

        fun expression(): Node {
            var left = term()
            while (true) {
                val c = peek()
                if (c == '+' || c == '-') { i++; left = Bin(c, left, term()) } else return left
            }
        }

        private fun term(): Node {
            var left = unary()
            while (true) {
                val c = peek()
                if (c == '*' || c == '/') { i++; left = Bin(c, left, unary()) } else return left
            }
        }

        private fun unary(): Node {
            if (peek() == '-') { i++; return Neg(unary()) }
            return primary()
        }

        private fun primary(): Node {
            val c = peek() ?: throw IllegalArgumentException("Formula ends too early")
            return when {
                c == '(' -> {
                    i++
                    val n = expression()
                    if (peek() != ')') throw IllegalArgumentException("Missing ')'")
                    i++
                    n
                }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    Num(s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("Bad number '${s.substring(start, i)}'"))
                }
                c.isLetter() -> {
                    val start = i
                    while (i < s.length && s[i].isLetterOrDigit()) i++
                    val word = s.substring(start, i).uppercase()
                    if (word.length == 1 && word[0] in 'A'..'H') return Var(word[0] - 'A')
                    val n = arity[word] ?: throw IllegalArgumentException("Unknown name '$word' (use A–H or a function)")
                    if (peek() != '(') throw IllegalArgumentException("$word needs '('")
                    i++
                    val args = mutableListOf(expression())
                    while (peek() == ',') { i++; args += expression() }
                    if (peek() != ')') throw IllegalArgumentException("Missing ')' after $word")
                    i++
                    if (args.size != n) throw IllegalArgumentException("$word takes $n argument(s)")
                    Call(word, args)
                }
                else -> throw IllegalArgumentException("Unexpected '$c' at position ${i + 1}")
            }
        }
    }
}
