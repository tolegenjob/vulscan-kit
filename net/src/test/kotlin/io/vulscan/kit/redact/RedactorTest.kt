package io.vulscan.kit.redact

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RedactorTest {

    @Test
    fun `sensitive headers are masked and others kept`() {
        val result = Redactor.headers(
            mapOf(
                "Authorization" to "Bearer abc",
                "Cookie" to "sid=1",
                "Set-Cookie" to "sid=2",
                "X-Api-Key" to "k",
                "X-Custom-Token" to "t",
                "Accept" to "text/html",
            ),
        )
        assertThat(result["Authorization"]).isEqualTo(Redactor.MASK)
        assertThat(result["Cookie"]).isEqualTo(Redactor.MASK)
        assertThat(result["Set-Cookie"]).isEqualTo(Redactor.MASK)
        assertThat(result["X-Api-Key"]).isEqualTo(Redactor.MASK)
        assertThat(result["X-Custom-Token"]).isEqualTo(Redactor.MASK)
        assertThat(result["Accept"]).isEqualTo("text/html")
    }

    @ParameterizedTest
    @ValueSource(strings = ["password", "Password", "user_password", "access_token", "accessToken", "api-key", "apiKey", "JSESSIONID", "csrfToken", "auth", "sid"])
    fun `sensitive names are recognized`(name: String) {
        assertThat(Redactor.isSensitiveName(name)).`as`(name).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["q", "id", "author", "page", "sort", "name", "authority"])
    fun `ordinary names are not sensitive`(name: String) {
        assertThat(Redactor.isSensitiveName(name)).`as`(name).isFalse()
    }

    @Test
    fun `url masks userinfo and sensitive query values only`() {
        val masked = Redactor.url("http://user:pw@host:8080/p?q=hello&token=abc&page=2#frag")
        assertThat(masked).isEqualTo("http://${Redactor.MASK}@host:8080/p?q=hello&token=${Redactor.MASK}&page=2#frag")
        assertThat(Redactor.url("http://host/p?q=1")).isEqualTo("http://host/p?q=1")
    }

    @Test
    fun `form body is masked by parameter name`() {
        assertThat(Redactor.body("username=alice&password=S3cret!&remember=1"))
            .isEqualTo("username=alice&password=${Redactor.MASK}&remember=1")
    }

    @Test
    fun `json body is masked by key at any depth`() {
        val body = """{"username":"alice","password":"S3cret!","nested":{"apiKey":"abc","count":3},"token":123}"""
        val masked = Redactor.body(body)
        assertThat(masked).doesNotContain("S3cret!").doesNotContain("abc")
        assertThat(masked).contains("\"username\":\"alice\"").contains("\"count\":3")
        assertThat(masked).contains("\"password\":\"${Redactor.MASK}\"").contains("\"token\":\"${Redactor.MASK}\"")
    }

    @Test
    fun `plain text key value pairs are masked`() {
        val masked = Redactor.body("login failed for password=Hunter2 retry later")
        assertThat(masked).doesNotContain("Hunter2").contains("password=${Redactor.MASK}")
    }

    @Test
    fun `html is left untouched`() {
        val html = "<p>Results for <b>q</b> = 1</p>"
        assertThat(Redactor.body(html)).isEqualTo(html)
    }

    @Test
    fun `excerpt truncates within the limit and keeps surrogate pairs intact`() {
        val short = "abc"
        assertThat(Redactor.excerpt(short, 10)).isEqualTo(short)

        val text = "a".repeat(100) + "😀" + "b".repeat(100)
        val result = Redactor.excerpt(text, 105)
        assertThat(result.length).isLessThanOrEqualTo(105)
        assertThat(result).endsWith("…[truncated]")
        assertThat(result.any { Character.isHighSurrogate(it) && result.indexOf(it) == result.length - 1 }).isFalse()
    }
}
