package com.patron.snaglite.download.resolvers

import android.util.Log

/**
 * Decoder for the standard Dean Edwards P.A.C.K.E.R. obfuscation:
 * `eval(function(p,a,c,k,e,d){...}('payload',base,count,'k1|k2|...'.split('|')))`.
 *
 * Avoids triple-quoted raw strings (some Android compilers / lints choke on the
 * mix of regex meta-chars + raw-string lexing) and avoids `[\s\S]` / nested
 * negated char classes (cheaper and more portable across regex implementations).
 *
 * Static initialisation is wrapped in `runCatching` so a regex-engine quirk on
 * any single device can never throw `ExceptionInInitializerError` — the unpacker
 * just degrades to "no match" and the caller surfaces a clean error.
 */
object Packer {

    private const val TAG = "Packer"
    private const val PH = ""

    // STRICT: standard packer header. Uses `.` with DOT_MATCHES_ALL.
    private val STRICT: Regex? = compile(
        "eval\\(function\\(p,a,c,k,e,[a-z]\\)\\{.*?\\}\\s*\\(\\s*'(.+?)'\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*'(.*?)'\\s*\\.split\\('\\|'\\)",
    )

    // RELAXED: tolerates whitespace/newlines around the call. Same capture groups.
    private val RELAXED: Regex? = compile(
        "eval\\s*\\(\\s*function\\s*\\(\\s*p\\s*,\\s*a\\s*,\\s*c\\s*,\\s*k\\s*,\\s*e\\s*,\\s*[a-z]\\s*\\)\\s*\\{.*?\\}\\s*\\(\\s*'(.+?)'\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*'(.*?)'\\s*\\.split\\(",
    )

    private fun compile(pattern: String): Regex? = try {
        Regex(pattern, RegexOption.DOT_MATCHES_ALL)
    } catch (t: Throwable) {
        Log.e(TAG, "regex compile failed for: $pattern", t)
        null
    }

    fun unpack(js: String): String? {
        val strict = STRICT
        if (strict != null) {
            val m = strict.find(js)
            if (m != null) {
                Log.i(TAG, "strict hit (p=${m.groupValues[1].length}, a=${m.groupValues[2]}, c=${m.groupValues[3]})")
                return decode(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4])
            }
            Log.w(TAG, "strict miss")
        } else {
            Log.w(TAG, "strict regex unavailable")
        }
        val relaxed = RELAXED
        if (relaxed != null) {
            val m = relaxed.find(js)
            if (m != null) {
                Log.i(TAG, "relaxed hit (p=${m.groupValues[1].length}, a=${m.groupValues[2]}, c=${m.groupValues[3]})")
                return decode(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4])
            }
            Log.w(TAG, "relaxed miss")
        }
        return null
    }

    private fun decode(rawP: String, a: Int, c: Int, kRaw: String): String {
        var p = rawP.replace("\\\\", PH).replace("\\'", "'").replace(PH, "\\")
        val k = kRaw.split('|')
        for (i in (c - 1) downTo 0) {
            val key = k.getOrNull(i) ?: continue
            if (key.isEmpty()) continue
            val token = unbase(i, a)
            p = Regex("\\b" + Regex.escape(token) + "\\b").replace(p) { key }
        }
        return p
    }

    private fun unbase(num: Int, base: Int): String {
        if (num == 0) return "0"
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(chars[n % base])
            n /= base
        }
        return sb.reverse().toString()
    }
}
