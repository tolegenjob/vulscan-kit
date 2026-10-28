package io.vulscan.kit.net

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** Источник DNS-ответов; в тестах подменяется. */
fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>
}

object SystemHostResolver : HostResolver {
    override fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}

/**
 * DNS-резолвер для OkHttp: возвращает только проверенные адреса. Если хотя бы один адрес запрещён
 * политикой, отклоняется весь запрос. OkHttp подключается к возвращённым адресам без повторного
 * разрешения имени, поэтому проверенный и фактический адрес совпадают.
 */
class SafeDns(
    private val policy: IpPolicy,
    private val resolver: HostResolver = SystemHostResolver,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val literal = IpLiteral.parse(hostname)
        val addresses = if (literal != null) {
            listOf(literal)
        } else {
            if (NUMERIC_LOOKALIKE.matches(hostname)) {
                throw ScopeViolationException(ScopeDenial.BAD_HOST, "Ambiguous numeric host: $hostname")
            }
            resolver.resolve(hostname)
        }
        if (addresses.isEmpty()) throw UnknownHostException("No addresses for $hostname")
        addresses.forEach { address ->
            val verdict = policy.check(address)
            if (verdict != IpVerdict.ALLOWED) {
                throw ScopeViolationException(
                    ScopeDenial.PRIVATE_ADDRESS,
                    "Host $hostname resolves to a forbidden address ($verdict)",
                )
            }
        }
        return addresses
    }

    private companion object {
        /** Десятичные, восьмеричные и шестнадцатеричные формы IPv4 (`2130706433`, `0177.0.0.1`, `0x7f.1`). */
        val NUMERIC_LOOKALIKE = Regex("^(0[xX][0-9a-fA-F]+|[0-9]+)(\\.(0[xX][0-9a-fA-F]+|[0-9]+))*$")
    }
}
