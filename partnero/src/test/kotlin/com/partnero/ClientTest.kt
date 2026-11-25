package com.partnero

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ClientTest {
    private val storage = MemoryStorage()
    private val installStorage = MemoryStorage()
    private val transport = FakeTransport()
    private var referrer: InstallReferrer = InstallReferrer.Found("ref=PARTNER1&utm_source=google-play")
    private var referrerReads = 0

    private fun client(
        programId: String = "YB9YT4ER",
        baseUrl: String = "https://app.partnero.com/",
        referralParameter: String = "ref",
    ) = Client(
        programId = programId,
        baseUrl = baseUrl,
        referralParameter = referralParameter,
        storage = storage,
        installStorage = installStorage,
        transport = transport,
        installReferrer = { referrerReads++; referrer },
        clock = { 1_794_900_000_000 },
    )

    @Test
    fun `records the first launch once`() {
        client()
        assertEquals("2026-11-17T07:20:00.000Z", storage.getString(Client.FIRST_SEEN_AT_KEY))

        storage.putString(Client.FIRST_SEEN_AT_KEY, "2026-01-01T00:00:00.000Z")
        client()
        assertEquals("2026-01-01T00:00:00.000Z", storage.getString(Client.FIRST_SEEN_AT_KEY))
    }

    @Test
    fun `posts the claim to the program's endpoint`() = runTest {
        transport.respond(200, claimed("user_42"))

        val result = client().claim("  PARTNER1 ", " user_42 ", CodeType.PROMOTION_CODE)

        assertEquals(ClaimResult(ClaimResult.Status.CLAIMED, "user_42"), result)
        assertEquals("https://app.partnero.com/api/pub/v1/mobile/YB9YT4ER/claims", transport.url)
        val body = JSONObject(transport.body!!)
        assertEquals("user_42", body.getString("app_user_id"))
        assertEquals("PARTNER1", body.getString("code"))
        assertEquals("promotion_code", body.getString("code_type"))
        assertEquals("android", body.getString("platform"))
        assertEquals("2026-11-17T07:20:00.000Z", body.getString("first_seen_at"))
        assertEquals(5, body.length())
    }

    @Test
    fun `reports an existing attribution`() = runTest {
        transport.respond(200, """{"status":1,"data":{"status":"already_claimed","app_user_id":"user@example.com"}}""")

        val result = client().claim("PARTNER1", "user@example.com", CodeType.AUTO)

        assertEquals(ClaimResult.Status.ALREADY_CLAIMED, result.status)
    }

    @Test
    fun `rejects invalid input without a request`() = runTest {
        val cases = listOf(
            Triple("", "PARTNER1", "user_42") to PartneroException.Field.PROGRAM_ID,
            Triple("YB9/../x", "PARTNER1", "user_42") to PartneroException.Field.PROGRAM_ID,
            Triple("YB9YT4ER", " ", "user_42") to PartneroException.Field.CODE,
            Triple("YB9YT4ER", "PART NER", "user_42") to PartneroException.Field.CODE,
            Triple("YB9YT4ER", "A".repeat(101), "user_42") to PartneroException.Field.CODE,
            Triple("YB9YT4ER", "PARTNER1", "") to PartneroException.Field.APP_USER_ID,
            Triple("YB9YT4ER", "PARTNER1", "\$RCAnonymousID:8b1f0e") to PartneroException.Field.APP_USER_ID,
            Triple("YB9YT4ER", "PARTNER1", "a".repeat(256)) to PartneroException.Field.APP_USER_ID,
            Triple("YB9YT4ER", "PARTNER1", "<script>@x.y") to PartneroException.Field.APP_USER_ID,
        )

        for ((input, field) in cases) {
            val (programId, code, appUserId) = input
            val error = expect<PartneroException.InvalidInput> { client(programId).claim(code, appUserId, CodeType.AUTO) }
            assertEquals("$input", field, error.field)
        }
        assertEquals(0, transport.calls)
    }

    @Test
    fun `names the invalid field in plain words`() = runTest {
        val error = expect<PartneroException.InvalidInput> { client().claim("PARTNER1", " ", CodeType.AUTO) }

        assertEquals("The app user ID is blank or not valid.", error.message)
    }

    @Test
    fun `accepts email app user ids`() {
        assertTrue(Client.isValidAppUserId("jane.doe+ios@example.com"))
        assertTrue(Client.isValidAppUserId("a".repeat(255)))
    }

    @Test
    fun `maps error responses`() = runTest {
        transport.respond(400, """{"status":0,"message":"Invalid","errors":{"code":["Bad"],"platform":["Bad"]}}""")
        assertEquals(PartneroException.Field.CODE, expect<PartneroException.InvalidInput> { claim() }.field)

        transport.respond(422, """{"errors":{"unknown":["Bad"]}}""")
        assertEquals(PartneroException.Field.REQUEST, expect<PartneroException.InvalidInput> { claim() }.field)

        transport.respond(404, "<html>Not found</html>")
        expect<PartneroException.CodeNotFound> { claim() }

        transport.respond(409, """{"status":0,"message":"The claim window has closed."}""")
        assertEquals("The claim window has closed.", expect<PartneroException.CodeConflict> { claim() }.message)

        transport.respond(429, "")
        expect<PartneroException.RateLimited> { claim() }

        transport.respond(503, """{"message":"Down for maintenance"}""")
        val server = expect<PartneroException.Server> { claim() }
        assertEquals(503, server.status)
        assertEquals("Down for maintenance", server.message)
    }

    @Test
    fun `rejects an unexpected success body`() = runTest {
        for (body in listOf(
            "",
            "{}",
            """{"data":{"status":"pending","app_user_id":"user_42"}}""",
            """{"data":{"status":"claimed","app_user_id":null}}""",
        )) {
            transport.respond(200, body)
            assertEquals(200, expect<PartneroException.Server> { claim() }.status)
        }
    }

    @Test
    fun `passes network failures through`() = runTest {
        transport.fail(IOException("offline"))

        assertEquals("offline", expect<IOException> { claim() }.message)
    }

    @Test
    fun `reads the code from a link query`() {
        val client = client(referralParameter = "aff")

        assertEquals("PARTNER1", client.code("utm_source=x&AFF=PARTNER1&aff=OTHER"))
        assertEquals("PART.NER-1", client.code("aff=PART.NER%2D1"))
        assertNull(client.code("aff="))
        assertNull(client.code("aff"))
        assertNull(client.code("ref=PARTNER1"))
        assertNull(client.code(null))
    }

    @Test
    fun `rejects a blank referral parameter`() {
        val error = expect<PartneroException.InvalidInput> { client(referralParameter = " ").code("ref=PARTNER1") }

        assertEquals(PartneroException.Field.REFERRAL_PARAMETER, error.field)
    }

    @Test
    fun `claims a link and ignores one without a code`() = runTest {
        transport.respond(200, claimed("user_42"))

        assertNull(client().claimQuery("utm_source=x", "user_42"))
        assertEquals(0, transport.calls)
        assertEquals(ClaimResult.Status.CLAIMED, client().claimQuery("ref=PARTNER1", "user_42")?.status)
        assertEquals("auto", JSONObject(transport.body!!).getString("code_type"))
    }

    @Test
    fun `sends the click ID with the code`() = runTest {
        transport.respond(200, claimed("user_42"))

        client().claim("PARTNER1", "user_42", CodeType.AUTO, " a1B2c3 ")

        val body = JSONObject(transport.body!!)
        assertEquals("PARTNER1", body.getString("code"))
        assertEquals("a1B2c3", body.getString("click_id"))
    }

    @Test
    fun `claims by click ID alone`() = runTest {
        transport.respond(200, claimed("user_42"))

        client().claim(null, "user_42", CodeType.AUTO, "a1B2c3")

        val body = JSONObject(transport.body!!)
        assertEquals("a1B2c3", body.getString("click_id"))
        assertTrue(!body.has("code") && !body.has("code_type"))
        assertEquals(4, body.length())
    }

    @Test
    fun `rejects a malformed click ID without a request`() = runTest {
        for (clickId in listOf("a1-b2", "a1 b2", "a".repeat(33))) {
            val error = expect<PartneroException.InvalidInput> { client().claim(null, "user_42", CodeType.AUTO, clickId) }
            assertEquals(PartneroException.Field.CLICK_ID, error.field)
        }
        assertEquals(PartneroException.Field.CODE, expect<PartneroException.InvalidInput> { client().claim(null, "user_42", CodeType.AUTO) }.field)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `reads a Partnero app link`() {
        val client = client()

        assertEquals("PARTNER1", client.code("partnero_code=PARTNER1&partnero_click=a1B2c3&ref=OTHER"))
        assertEquals("a1B2c3", client.clickId("partnero_code=PARTNER1&partnero_click=a1B2c3"))
        assertNull(client.clickId("partnero_click=not-valid"))
        assertNull(client.clickId("ref=PARTNER1"))
    }

    @Test
    fun `claims a link with only a click`() = runTest {
        transport.respond(200, claimed("user_42"))

        assertEquals(ClaimResult.Status.CLAIMED, client().claimQuery("partnero_click=a1B2c3", "user_42")?.status)

        val body = JSONObject(transport.body!!)
        assertEquals("a1B2c3", body.getString("click_id"))
        assertTrue(!body.has("code"))
    }

    @Test
    fun `claims an app link install referrer with its click`() = runTest {
        transport.respond(200, claimed("user_42"))
        referrer = InstallReferrer.Found("partnero_code=PARTNER1&partnero_click=a1B2c3")

        assertEquals(ClaimResult.Status.CLAIMED, client().claimFromInstallReferrer("user_42")?.status)

        val body = JSONObject(transport.body!!)
        assertEquals("PARTNER1", body.getString("code"))
        assertEquals("a1B2c3", body.getString("click_id"))
    }

    @Test
    fun `claims the install referrer once`() = runTest {
        transport.respond(200, claimed("user_42"))
        val client = client()

        assertEquals(ClaimResult.Status.CLAIMED, client.claimFromInstallReferrer("user_42")?.status)
        assertNull(client.claimFromInstallReferrer("user_42"))
        assertNull(client().claimFromInstallReferrer("user_42"))

        assertEquals(1, transport.calls)
        assertEquals(1, referrerReads)
        assertEquals("PARTNER1", JSONObject(transport.body!!).getString("code"))
    }

    @Test
    fun `claims the install referrer once under concurrent calls`() = runTest {
        transport.respond(200, claimed("user_42"))
        transport.delayMillis = 100
        val clients = listOf(client(), client())

        val results = List(6) { async { clients[it % 2].claimFromInstallReferrer("user_42") } }.map { it.await() }

        assertEquals(1, results.count { it != null })
        assertEquals(1, transport.calls)
    }

    @Test
    fun `finishes when there is no install referrer`() = runTest {
        for (missing in listOf(InstallReferrer.None, InstallReferrer.Found("utm_source=google-play"))) {
            installStorage.clear()
            referrer = missing

            assertNull(client().claimFromInstallReferrer("user_42"))
            assertTrue(installStorage.getString(Client.INSTALL_REFERRER_KEY) != null)
        }
        assertEquals(0, transport.calls)
    }

    @Test
    fun `asks Google Play again when it was unavailable`() = runTest {
        referrer = InstallReferrer.Unavailable
        assertNull(client().claimFromInstallReferrer("user_42"))
        assertNull(installStorage.getString(Client.INSTALL_REFERRER_KEY))

        referrer = InstallReferrer.Found("ref=PARTNER1")
        transport.respond(200, claimed("user_42"))
        assertEquals(ClaimResult.Status.CLAIMED, client().claimFromInstallReferrer("user_42")?.status)
    }

    @Test
    fun `retries the install referrer after temporary failures`() = runTest {
        transport.respond(429, "")
        expect<PartneroException.RateLimited> { client().claimFromInstallReferrer("user_42") }
        transport.respond(500, "")
        expect<PartneroException.Server> { client().claimFromInstallReferrer("user_42") }
        transport.fail(IOException("offline"))
        expect<IOException> { client().claimFromInstallReferrer("user_42") }
        assertNull(installStorage.getString(Client.INSTALL_REFERRER_KEY))

        transport.respond(200, claimed("user_42"))
        assertEquals(ClaimResult.Status.CLAIMED, client().claimFromInstallReferrer("user_42")?.status)
        assertEquals(4, transport.calls)
    }

    @Test
    fun `gives up on the install referrer after a final refusal`() = runTest {
        for ((status, body) in listOf(404 to "", 409 to """{"message":"Closed"}""", 400 to """{"errors":{"code":["Bad"]}}""")) {
            installStorage.clear()
            transport.respond(status, body)

            expect<PartneroException> { client().claimFromInstallReferrer("user_42") }
            assertNull(client().claimFromInstallReferrer("user_42"))
        }

        installStorage.clear()
        referrer = InstallReferrer.Found("ref=NOT%20VALID")
        expect<PartneroException.InvalidInput> { client().claimFromInstallReferrer("user_42") }
        assertNull(client().claimFromInstallReferrer("user_42"))
    }

    @Test
    fun `keeps the install referrer when the app's own input is invalid`() = runTest {
        expect<PartneroException.InvalidInput> { client().claimFromInstallReferrer("\$RCAnonymousID:8b1f0e") }
        expect<PartneroException.InvalidInput> { client(programId = "").claimFromInstallReferrer("user_42") }
        expect<PartneroException.InvalidInput> { client(referralParameter = "").claimFromInstallReferrer("user_42") }

        assertNull(installStorage.getString(Client.INSTALL_REFERRER_KEY))
        assertEquals(0, transport.calls)
    }

    private suspend fun claim() = client().claim("PARTNER1", "user_42", CodeType.AUTO)

    private fun claimed(appUserId: String) = """{"status":1,"data":{"status":"claimed","app_user_id":"$appUserId"}}"""

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Expected ${T::class.simpleName}, got $e", e)
        }
        fail("Expected ${T::class.simpleName}")
        throw IllegalStateException()
    }
}

private class MemoryStorage : Storage {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    fun clear() = values.clear()
}

private class FakeTransport : Transport {
    private var response: Result<HttpResponse> = Result.failure(IllegalStateException("No response set"))
    var calls = 0
    var delayMillis = 0L
    var url: String? = null
    var body: String? = null

    fun respond(status: Int, body: String) {
        response = Result.success(HttpResponse(status, body))
    }

    fun fail(error: Throwable) {
        response = Result.failure(error)
    }

    override suspend fun post(url: String, json: String): HttpResponse {
        calls++
        this.url = url
        body = json
        delay(delayMillis)
        return response.getOrThrow()
    }
}
