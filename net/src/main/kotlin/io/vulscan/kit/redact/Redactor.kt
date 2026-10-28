package io.vulscan.kit.redact

/** Маскирование секретов в заголовках, URL, телах и логах. Применяется перед записью любых данных запроса/ответа. */
object Redactor {
    const val MASK = "[REDACTED]"
    private const val TRUNCATED_SUFFIX = "…[truncated]"

    private val SENSITIVE_HEADERS = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie",
        "x-api-key", "x-auth-token", "x-csrf-token", "x-xsrf-token",
    )

    private val SENSITIVE_FRAGMENTS = listOf(
        "password", "passwd", "pwd", "secret", "token", "session", "apikey",
        "authorization", "jwt", "csrf", "xsrf", "cookie", "signature", "credential",
    )

    private val SENSITIVE_EXACT = setOf("auth", "sid", "sessid", "key")

    private val USERINFO = Regex("(//)[^/@\\s]+@")
    private val JSON_PAIR = Regex("(\"(?:[^\"\\\\]|\\\\.)*\")(\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?|true|false|null)")
    private val KEY_VALUE_TEXT = Regex("(?i)\\b([a-z0-9_.-]*(?:password|passwd|pwd|secret|token|apikey|api_key|api-key)[a-z0-9_.-]*)(\\s*[:=]\\s*)([^\\s&;,\"']+)")

    /** Имя считается чувствительным, если после удаления `_ - .` оно содержит известный фрагмент. */
    fun isSensitiveName(name: String): Boolean {
        val normalized = name.lowercase().replace(Regex("[_.\\-\\[\\]]"), "")
        return normalized in SENSITIVE_EXACT || SENSITIVE_FRAGMENTS.any { normalized.contains(it) }
    }

    fun headers(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) ->
            if (name.lowercase() in SENSITIVE_HEADERS || isSensitiveName(name)) MASK else value
        }

    /** Маскирует userinfo и значения чувствительных параметров query. */
    fun url(url: String): String {
        val withoutUserinfo = USERINFO.replace(url) { "${it.groupValues[1]}$MASK@" }
        val hashIndex = withoutUserinfo.indexOf('#')
        val beforeFragment = if (hashIndex >= 0) withoutUserinfo.substring(0, hashIndex) else withoutUserinfo
        val fragment = if (hashIndex >= 0) withoutUserinfo.substring(hashIndex) else ""
        val queryIndex = beforeFragment.indexOf('?')
        if (queryIndex < 0) return withoutUserinfo
        val base = beforeFragment.substring(0, queryIndex)
        val query = beforeFragment.substring(queryIndex + 1)
        return "$base?${formEncoded(query)}$fragment"
    }

    /** Маскирует тело: JSON (по ключам), application/x-www-form-urlencoded (по именам) и произвольный текст. */
    fun body(text: String): String {
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> json(text)
            looksLikeForm(trimmed) -> formEncoded(text)
            else -> KEY_VALUE_TEXT.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        }
    }

    /** Обрезает текст до [max] символов вместе с суффиксом, не разрывая суррогатную пару. */
    fun excerpt(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = (max - TRUNCATED_SUFFIX.length).coerceAtLeast(0)
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end) + TRUNCATED_SUFFIX
    }

    private fun json(text: String): String =
        JSON_PAIR.replace(text) { match ->
            val key = match.groupValues[1].trim('"')
            if (isSensitiveName(key)) "${match.groupValues[1]}${match.groupValues[2]}\"$MASK\"" else match.value
        }

    private fun looksLikeForm(text: String): Boolean =
        text.contains('=') && !text.contains('<') && !text.contains(' ') && !text.contains('\n')

    private fun formEncoded(query: String): String =
        query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) {
                pair
            } else {
                val name = pair.substring(0, eq)
                if (isSensitiveName(name)) "$name=$MASK" else pair
            }
        }
}
