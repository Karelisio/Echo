package com.karelisio.echo.data.relay

import com.karelisio.echo.data.pairing.PairCredentials
import com.karelisio.echo.util.await
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class CreatePairResponse(val pairId: String, val secret: String, val role: String, val code: String, val expiresAt: Long)

@Serializable
data class JoinPairResponse(val pairId: String, val secret: String, val role: String)

@Serializable
data class StatusResponse(val paired: Boolean, val role: String, val pairedAt: Long? = null)

@Serializable
data class PingResponse(val sentAt: Long, val delayed: Int? = null)

@Serializable
private data class ErrorResponse(val error: String? = null, val message: String? = null)

@Serializable
private data class TokenBody(val token: String)

@Serializable
private data class JoinBody(val code: String, val token: String)

@Serializable
private data class PingBody(
    val emoji: String,
    val pattern: String,
    val text: String? = null,
    val target: String? = null,
    val delay: Int? = null,
)

@Serializable
private class EmptyBody

class RelayException(
    val kind: Kind,
    val status: Int = 0,
    /** Code d'erreur renvoyé par le Worker (ex. « code_invalid », « rate_limited »). */
    val code: String? = null,
    cause: Throwable? = null,
) : Exception("${kind.name} $status ${code.orEmpty()}".trim(), cause) {
    enum class Kind { NotConfigured, Network, Http }

    val isNetwork get() = kind == Kind.Network
    val isPairMissing get() = code == "pair_not_found"
}

/** Client HTTP du relais Cloudflare Worker (voir /worker). */
class RelayClient(baseUrl: String, private val http: OkHttpClient, private val json: Json) {

    private val baseUrl = baseUrl.trimEnd('/')

    val isConfigured: Boolean get() = baseUrl.startsWith("https://")

    val displayHost: String get() = baseUrl.removePrefix("https://").ifEmpty { "—" }

    suspend fun createPair(fcmToken: String): CreatePairResponse =
        post("/pair/create", TokenBody(fcmToken), credentials = null)

    suspend fun joinPair(code: String, fcmToken: String): JoinPairResponse =
        post("/pair/join", JoinBody(code, fcmToken), credentials = null)

    suspend fun status(credentials: PairCredentials): StatusResponse =
        post("/pair/status", EmptyBody(), credentials)

    suspend fun leave(credentials: PairCredentials) {
        post<EmptyBody, Unit>("/pair/leave", EmptyBody(), credentials)
    }

    suspend fun updateToken(credentials: PairCredentials, fcmToken: String) {
        post<TokenBody, Unit>("/token/update", TokenBody(fcmToken), credentials)
    }

    /** Écho pour l'autre téléphone ; [text] s'affiche dans sa notification (phrase par défaut si `null`). */
    suspend fun ping(credentials: PairCredentials, emoji: String, pattern: String, text: String? = null): PingResponse =
        post("/ping", PingBody(emoji, pattern, text), credentials)

    /** Écho de test renvoyé à ce même téléphone, éventuellement différé de [delaySeconds]. */
    suspend fun pingSelf(credentials: PairCredentials, emoji: String, pattern: String, delaySeconds: Int): PingResponse =
        post("/ping", PingBody(emoji, pattern, target = "self", delay = delaySeconds), credentials)

    private suspend inline fun <reified B, reified R> post(path: String, body: B, credentials: PairCredentials?): R {
        val text = execute(path, json.encodeToString(body), credentials)
        return if (R::class == Unit::class) Unit as R else json.decodeFromString(text)
    }

    private suspend fun execute(path: String, body: String, credentials: PairCredentials?): String {
        if (!isConfigured) throw RelayException(RelayException.Kind.NotConfigured)
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (credentials != null) {
                    header("Authorization", "Bearer ${credentials.secret}")
                    header("X-Pair-Id", credentials.pairId)
                }
            }
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw RelayException(RelayException.Kind.Network, cause = e)
        }
        return withContext(Dispatchers.IO) {
            response.use {
                val text = try {
                    it.body.string()
                } catch (e: IOException) {
                    throw RelayException(RelayException.Kind.Network, cause = e)
                }
                if (!it.isSuccessful) {
                    val error = runCatching { json.decodeFromString<ErrorResponse>(text) }.getOrNull()
                    throw RelayException(RelayException.Kind.Http, it.code, error?.error)
                }
                text
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
