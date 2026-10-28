package io.vulscan.kit.net

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class IpPolicyTest {

    private fun address(text: String) = IpLiteral.parse(text) ?: error("not a literal: $text")

    @ParameterizedTest
    @CsvSource(
        "127.0.0.1,LOOPBACK",
        "127.255.255.254,LOOPBACK",
        "10.0.0.5,PRIVATE",
        "172.16.5.4,PRIVATE",
        "172.31.255.255,PRIVATE",
        "172.32.0.1,ALLOWED",
        "192.168.1.1,PRIVATE",
        "169.254.169.254,LINK_LOCAL",
        "100.64.0.1,CGNAT",
        "0.0.0.0,UNSPECIFIED",
        "224.0.0.1,MULTICAST",
        "255.255.255.255,BROADCAST",
        "240.0.0.1,RESERVED",
        "192.0.2.10,RESERVED",
        "8.8.8.8,ALLOWED",
        "93.184.216.34,ALLOWED",
        "::1,LOOPBACK",
        "::,UNSPECIFIED",
        "fe80::1,LINK_LOCAL",
        "fc00::1,PRIVATE",
        "fd12:3456::1,PRIVATE",
        "ff02::1,MULTICAST",
        "2001:db8::1,RESERVED",
        "2606:4700::1111,ALLOWED",
        "::ffff:127.0.0.1,LOOPBACK",
        "::ffff:10.0.0.1,PRIVATE",
        "::ffff:169.254.169.254,LINK_LOCAL",
        "::127.0.0.1,LOOPBACK",
    )
    fun `default policy classifies addresses`(text: String, expected: IpVerdict) {
        assertThat(IpPolicy().check(address(text))).isEqualTo(expected)
    }

    @Test
    fun `allowlist can open loopback private and CGNAT only`() {
        val policy = IpPolicy(listOf(Cidr.parse("10.0.0.0/8"), Cidr.parse("127.0.0.0/8"), Cidr.parse("169.254.0.0/16")))
        assertThat(policy.check(address("10.1.2.3"))).isEqualTo(IpVerdict.ALLOWED)
        assertThat(policy.check(address("127.0.0.1"))).isEqualTo(IpVerdict.ALLOWED)
        // Адрес метаданных облака нельзя разрешить конфигурацией
        assertThat(policy.check(address("169.254.169.254"))).isEqualTo(IpVerdict.LINK_LOCAL)
        // Диапазон вне allowlist остаётся запрещённым
        assertThat(policy.check(address("192.168.0.1"))).isEqualTo(IpVerdict.PRIVATE)
    }

    @Test
    fun `cidr matching handles prefix boundaries`() {
        val cidr = Cidr.parse("172.28.0.0/14")
        assertThat(cidr.contains(address("172.28.0.1"))).isTrue()
        assertThat(cidr.contains(address("172.31.255.255"))).isTrue()
        assertThat(cidr.contains(address("172.32.0.0"))).isFalse()
        assertThat(cidr.contains(address("::1"))).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["2130706433", "0177.0.0.1", "0x7f.1", "127.1", "256.1.1.1", "1.2.3", "", "localhost", "01.2.3.4"])
    fun `non standard ip notations are not literals`(text: String) {
        assertThat(IpLiteral.parse(text)).isNull()
    }

    @Test
    fun `standard literals are parsed`() {
        assertThat(IpLiteral.parse("127.0.0.1")).isNotNull()
        assertThat(IpLiteral.parse("[::1]")).isNotNull()
        assertThat(IpLiteral.parse("2606:4700::1111")).isNotNull()
    }

    @Test
    fun `invalid cidr is rejected`() {
        assertThatThrownBy { Cidr.parse("10.0.0.0/33") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Cidr.parse("example.com/8") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
