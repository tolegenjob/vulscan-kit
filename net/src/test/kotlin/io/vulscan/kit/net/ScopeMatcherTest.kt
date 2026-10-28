package io.vulscan.kit.net

import io.vulscan.kit.contracts.AllowedHost
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ScopeMatcherTest {

    private val matcher = ScopeMatcher(
        allowedHosts = listOf(AllowedHost("lab-app", listOf(8080))),
        includePrefixes = listOf("http://lab-app:8080/"),
        excludePrefixes = listOf("http://lab-app:8080/logout", "http://lab-app:8080/admin/"),
    )

    @ParameterizedTest
    @CsvSource(
        "http://lab-app:8080/search?q=1,ALLOWED",
        "HTTP://LAB-APP:8080/search,ALLOWED",
        "http://lab-app:8080/logoutx,ALLOWED",
        "http://lab-app:8080/logout,EXCLUDED",
        "http://lab-app:8080/logout?x=1,EXCLUDED",
        "http://lab-app:8080/admin/users,EXCLUDED",
        "http://lab-app:8080/%2e%2e/admin/x,EXCLUDED",
        "http://lab-app:8080/a/../logout,EXCLUDED",
        "http://lab-app:8080@evil.test/,USERINFO_NOT_ALLOWED",
        "http://user:pw@lab-app:8080/,USERINFO_NOT_ALLOWED",
        "http://lab-app:8080.evil.test/,BAD_URL",
        "http://lab-app:8081/,PORT_NOT_ALLOWED",
        "http://other:8080/,HOST_NOT_ALLOWED",
        "ftp://lab-app:8080/,BAD_URL",
        "not a url,BAD_URL",
    )
    fun `url is decided on its canonical form`(url: String, expected: String) {
        val decision = matcher.check(url)
        if (expected == "ALLOWED") {
            assertThat(decision).isInstanceOf(ScopeDecision.Allowed::class.java)
        } else {
            assertThat(decision).isInstanceOf(ScopeDecision.Denied::class.java)
            assertThat((decision as ScopeDecision.Denied).reason).isEqualTo(ScopeDenial.valueOf(expected))
        }
    }

    @Test
    fun `trailing dot host is denied`() {
        assertThat(matcher.check("http://lab-app.:8080/")).isInstanceOf(ScopeDecision.Denied::class.java)
    }

    @Test
    fun `prefix without trailing slash respects segment boundary`() {
        val m = ScopeMatcher(listOf(AllowedHost("lab-app", listOf(8080))), listOf("http://lab-app:8080/api"))
        assertThat(m.check("http://lab-app:8080/api")).isInstanceOf(ScopeDecision.Allowed::class.java)
        assertThat(m.check("http://lab-app:8080/api/x")).isInstanceOf(ScopeDecision.Allowed::class.java)
        assertThat(m.check("http://lab-app:8080/api?x=1")).isInstanceOf(ScopeDecision.Allowed::class.java)
        val denied = m.check("http://lab-app:8080/apiary")
        assertThat((denied as ScopeDecision.Denied).reason).isEqualTo(ScopeDenial.OUT_OF_SCOPE)
    }

    @Test
    fun `invalid prefix fails fast`() {
        assertThatThrownBy {
            ScopeMatcher(listOf(AllowedHost("lab-app", listOf(8080))), listOf("not a url"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
