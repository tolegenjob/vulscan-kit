package io.vulscan.kit.net

import java.net.Inet6Address
import java.net.InetAddress

enum class IpVerdict {
    ALLOWED,
    LOOPBACK,
    UNSPECIFIED,
    LINK_LOCAL,
    PRIVATE,
    CGNAT,
    MULTICAST,
    BROADCAST,
    RESERVED,
}

/**
 * Политика допустимых адресов назначения. По умолчанию разрешены только глобально маршрутизируемые адреса.
 *
 * [allowedPrivate] (задаётся только конфигурацией, не заданием на сканирование) может разрешить
 * LOOPBACK, PRIVATE и CGNAT. Link-local (в том числе адрес метаданных облака 169.254.169.254),
 * unspecified, multicast, broadcast и зарезервированные диапазоны не разрешаются никогда.
 */
class IpPolicy(private val allowedPrivate: List<Cidr> = emptyList()) {

    fun check(address: InetAddress): IpVerdict {
        val verdict = classify(address)
        if (verdict == IpVerdict.ALLOWED) return verdict
        val overridable = verdict == IpVerdict.LOOPBACK || verdict == IpVerdict.PRIVATE || verdict == IpVerdict.CGNAT
        if (overridable && allowedPrivate.any { it.contains(address) }) return IpVerdict.ALLOWED
        return verdict
    }

    private fun classify(address: InetAddress): IpVerdict {
        val bytes = address.address
        if (bytes.size == 4) return classifyV4(address)
        for ((cidr, verdict) in V6_TABLE) {
            if (cidr.contains(address)) return verdict
        }
        // IPv4-совместимый адрес (::a.b.c.d); :: и ::1 уже обработаны таблицей выше
        if (address is Inet6Address && address.isIPv4CompatibleAddress) {
            return classifyV4(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        return IpVerdict.ALLOWED
    }

    private fun classifyV4(address: InetAddress): IpVerdict {
        for ((cidr, verdict) in V4_TABLE) {
            if (cidr.contains(address)) return verdict
        }
        return IpVerdict.ALLOWED
    }

    private companion object {
        // Порядок важен: побеждает первое совпадение, более узкие диапазоны идут раньше.
        val V4_TABLE: List<Pair<Cidr, IpVerdict>> = listOf(
            "255.255.255.255/32" to IpVerdict.BROADCAST,
            "0.0.0.0/8" to IpVerdict.UNSPECIFIED,
            "127.0.0.0/8" to IpVerdict.LOOPBACK,
            "10.0.0.0/8" to IpVerdict.PRIVATE,
            "172.16.0.0/12" to IpVerdict.PRIVATE,
            "192.168.0.0/16" to IpVerdict.PRIVATE,
            "169.254.0.0/16" to IpVerdict.LINK_LOCAL,
            "100.64.0.0/10" to IpVerdict.CGNAT,
            "224.0.0.0/4" to IpVerdict.MULTICAST,
            "192.0.0.0/24" to IpVerdict.RESERVED,
            "192.0.2.0/24" to IpVerdict.RESERVED,
            "198.18.0.0/15" to IpVerdict.RESERVED,
            "198.51.100.0/24" to IpVerdict.RESERVED,
            "203.0.113.0/24" to IpVerdict.RESERVED,
            "240.0.0.0/4" to IpVerdict.RESERVED,
        ).map { Cidr.parse(it.first) to it.second }

        val V6_TABLE: List<Pair<Cidr, IpVerdict>> = listOf(
            "::/128" to IpVerdict.UNSPECIFIED,
            "::1/128" to IpVerdict.LOOPBACK,
            "fe80::/10" to IpVerdict.LINK_LOCAL,
            "fc00::/7" to IpVerdict.PRIVATE,
            "ff00::/8" to IpVerdict.MULTICAST,
            "64:ff9b::/96" to IpVerdict.RESERVED,
            "100::/64" to IpVerdict.RESERVED,
            "2001:db8::/32" to IpVerdict.RESERVED,
            "2002::/16" to IpVerdict.RESERVED,
        ).map { Cidr.parse(it.first) to it.second }
    }
}
