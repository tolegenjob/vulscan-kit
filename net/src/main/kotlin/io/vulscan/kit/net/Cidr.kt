package io.vulscan.kit.net

import java.net.InetAddress
import java.net.UnknownHostException

/** Строгий разбор литералов IP без обращения к DNS. */
object IpLiteral {
    private const val OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
    private val IPV4 = Regex("^$OCTET(\\.$OCTET){3}$")
    private val IPV6_CHARS = Regex("^[0-9a-fA-F:.]+$")

    /**
     * Возвращает адрес для стандартной записи IPv4 (четыре десятичных октета без ведущих нулей)
     * или IPv6. Для всего остального, включая `2130706433`, `0177.0.0.1` и `0x7f.1`, возвращает null.
     */
    fun parse(text: String): InetAddress? {
        val t = text.removePrefix("[").removeSuffix("]")
        if (IPV4.matches(t)) {
            val bytes = t.split('.').map { it.toInt().toByte() }.toByteArray()
            return InetAddress.getByAddress(bytes)
        }
        if (t.contains(':') && IPV6_CHARS.matches(t)) {
            return try {
                InetAddress.getByName(t)
            } catch (_: UnknownHostException) {
                null
            }
        }
        return null
    }
}

/** Сеть в нотации CIDR для IPv4 и IPv6. */
class Cidr private constructor(
    private val network: ByteArray,
    private val prefixLength: Int,
) {
    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != network.size) return false
        val fullBytes = prefixLength / 8
        for (i in 0 until fullBytes) {
            if (bytes[i] != network[i]) return false
        }
        val remainingBits = prefixLength % 8
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (bytes[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    override fun toString(): String = "${InetAddress.getByAddress(network).hostAddress}/$prefixLength"

    companion object {
        fun parse(text: String): Cidr {
            val parts = text.trim().split('/')
            require(parts.size <= 2) { "Invalid CIDR: $text" }
            val address = IpLiteral.parse(parts[0]) ?: throw IllegalArgumentException("Invalid CIDR address: $text")
            val bits = address.address.size * 8
            val prefix = if (parts.size == 2) {
                parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid CIDR prefix: $text")
            } else {
                bits
            }
            require(prefix in 0..bits) { "CIDR prefix out of range: $text" }
            return Cidr(address.address, prefix)
        }
    }
}
