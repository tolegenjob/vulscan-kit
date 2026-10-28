package io.vulscan.kit.net

import io.vulscan.kit.contracts.AllowedHost
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.UnknownHostException

enum class ScopeDenial {
    BAD_URL,
    SCHEME_NOT_ALLOWED,
    USERINFO_NOT_ALLOWED,
    BAD_HOST,
    HOST_NOT_ALLOWED,
    PORT_NOT_ALLOWED,
    OUT_OF_SCOPE,
    EXCLUDED,
    PRIVATE_ADDRESS,
    REDIRECT_LIMIT,
}

sealed interface ScopeDecision {
    data class Allowed(val url: HttpUrl) : ScopeDecision
    data class Denied(val reason: ScopeDenial, val detail: String) : ScopeDecision
}

/**
 * Нарушение scope. Наследует [UnknownHostException], чтобы OkHttp корректно прерывал соединение,
 * когда нарушение обнаружено при DNS-разрешении или в сетевом перехватчике.
 */
class ScopeViolationException(
    val reason: ScopeDenial,
    message: String,
) : UnknownHostException(message)

/**
 * Проверяет URL по спискам разрешённых хостов, портов и префиксов.
 * URL разбирается и нормализуется OkHttp (регистр хоста, точки в пути, `%2e%2e`, обратные слеши, фрагмент),
 * поэтому сравнение идёт по каноническому виду, а не по исходной строке.
 */
class ScopeMatcher(
    allowedHosts: List<AllowedHost>,
    includePrefixes: List<String>,
    excludePrefixes: List<String> = emptyList(),
) {
    private val hosts: Map<String, Set<Int>> =
        allowedHosts.associate { it.host.lowercase() to it.ports.toSet() }
    private val include: List<String> = includePrefixes.map { normalizePrefix(it) }
    private val exclude: List<String> = excludePrefixes.map { normalizePrefix(it) }

    fun check(rawUrl: String): ScopeDecision {
        val url = rawUrl.toHttpUrlOrNull()
            ?: return ScopeDecision.Denied(ScopeDenial.BAD_URL, "URL cannot be parsed")
        if (url.scheme != "http" && url.scheme != "https") {
            return ScopeDecision.Denied(ScopeDenial.SCHEME_NOT_ALLOWED, "Scheme ${url.scheme} is not allowed")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return ScopeDecision.Denied(ScopeDenial.USERINFO_NOT_ALLOWED, "Credentials in URL are not allowed")
        }
        val host = url.host.lowercase()
        if (host.endsWith(".")) {
            return ScopeDecision.Denied(ScopeDenial.BAD_HOST, "Trailing dot in host")
        }
        val ports = hosts[host]
            ?: return ScopeDecision.Denied(ScopeDenial.HOST_NOT_ALLOWED, "Host $host is not allowed")
        if (url.port !in ports) {
            return ScopeDecision.Denied(ScopeDenial.PORT_NOT_ALLOWED, "Port ${url.port} is not allowed for $host")
        }
        val normalized = canonical(url)
        if (exclude.any { matchesPrefix(normalized, it) }) {
            return ScopeDecision.Denied(ScopeDenial.EXCLUDED, "URL is excluded")
        }
        if (include.none { matchesPrefix(normalized, it) }) {
            return ScopeDecision.Denied(ScopeDenial.OUT_OF_SCOPE, "URL is outside include prefixes")
        }
        return ScopeDecision.Allowed(url)
    }

    private fun normalizePrefix(prefix: String): String {
        val url = prefix.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid scope prefix: $prefix")
        return canonical(url)
    }

    private fun canonical(url: HttpUrl): String = url.newBuilder().fragment(null).build().toString()

    /** Префикс должен заканчиваться границей сегмента: `/api` не совпадает с `/apiary`. */
    private fun matchesPrefix(url: String, prefix: String): Boolean {
        if (!url.startsWith(prefix)) return false
        if (prefix.endsWith("/") || url.length == prefix.length) return true
        return url[prefix.length] == '/' || url[prefix.length] == '?'
    }
}
