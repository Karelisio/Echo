package com.karelisio.echo.data.relay

import com.karelisio.echo.data.pairing.PairCredentials
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayClientTest {

    @Test
    fun envoieLaPhraseSeulementSiElleExiste() = runBlocking {
        val requests = mutableListOf<Pair<String, String>>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            requests += request.header("X-Pair-Id").orEmpty() to body
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"ok":true,"sentAt":1234}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val relay = RelayClient("https://relay.test/", http, Json { ignoreUnknownKeys = true; explicitNulls = false })
        val credentials = PairCredentials("pair-de-test-0123456789", "secret", "a")

        assertEquals(1234L, relay.ping(credentials, "💜", "heartbeat", "Tu me manques").sentAt)
        relay.ping(credentials, "💜", "heartbeat", null)

        assertEquals("pair-de-test-0123456789", requests[0].first)
        assertEquals("""{"emoji":"💜","pattern":"heartbeat","text":"Tu me manques"}""", requests[0].second)
        assertEquals("""{"emoji":"💜","pattern":"heartbeat"}""", requests[1].second)
    }
}
