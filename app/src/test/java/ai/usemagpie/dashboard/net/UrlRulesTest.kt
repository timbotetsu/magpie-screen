package ai.usemagpie.dashboard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlRulesTest {

    private fun base(input: String): String {
        val check = UrlRules.normalize(input)
        assertTrue("expected valid url for $input, got $check", check is UrlCheck.Ok)
        return (check as UrlCheck.Ok).base.toString()
    }

    private fun endpoint(input: String, path: String): String {
        val check = UrlRules.normalize(input) as UrlCheck.Ok
        return ConnectionSettings(check.base, "key")
            .url(Endpoints.segments(path), listOf("period" to "today"))
            .toString()
    }

    @Test
    fun `host with and without trailing v1 build the same usage path`() {
        assertEquals(
            "http://host:3425/v1/magpie/usage?period=today",
            endpoint("http://host:3425", Endpoints.USAGE),
        )
        assertEquals(
            "http://host:3425/v1/magpie/usage?period=today",
            endpoint("http://host:3425/v1/", Endpoints.USAGE),
        )
        assertEquals(
            "http://host:3425/v1/magpie/usage?period=today",
            endpoint("http://host:3425/", Endpoints.USAGE),
        )
    }

    @Test
    fun `proxy prefix is preserved`() {
        assertEquals("http://lan:8080/proxy/v1/magpie/usage?period=today", endpoint("http://lan:8080/proxy/", Endpoints.USAGE))
        assertEquals("http://lan:8080/proxy/v1/magpie/usage?period=today", endpoint("http://lan:8080/proxy/v1", Endpoints.USAGE))
    }

    @Test
    fun `requests endpoint stays under usage`() {
        assertEquals("http://h:1/v1/magpie/usage/requests?period=today", endpoint("http://h:1", Endpoints.REQUESTS))
        assertEquals("http://h:1/v1/magpie/quotas/cards?period=today", endpoint("http://h:1", Endpoints.QUOTA_CARDS))
    }

    @Test
    fun `empty input is rejected`() {
        assertTrue(UrlRules.normalize("   ") is UrlCheck.Err)
    }

    @Test
    fun `credentials query and fragment are rejected`() {
        assertTrue(UrlRules.normalize("http://user:pass@host:3425") is UrlCheck.Err)
        assertTrue(UrlRules.normalize("http://host:3425?a=1") is UrlCheck.Err)
        assertTrue(UrlRules.normalize("http://host:3425#frag") is UrlCheck.Err)
    }

    @Test
    fun `scheme is required`() {
        assertTrue(UrlRules.normalize("host:3425") is UrlCheck.Err)
        assertTrue(UrlRules.normalize("ftp://host:3425") is UrlCheck.Err)
    }

    @Test
    fun `blank key is rejected`() {
        assertEquals("请输入 API key", UrlRules.validateKey("  "))
        assertEquals(null, UrlRules.validateKey("abc"))
    }

    @Test
    fun `https keeps its scheme`() {
        assertEquals(
            "https://gw.example.com/v1/magpie/usage?period=today",
            endpoint("https://gw.example.com/", Endpoints.USAGE),
        )
    }
}
