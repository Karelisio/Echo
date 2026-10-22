package com.karelisio.echo.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Vérification et téléchargement des mises à jour, avec un faux serveur GitHub. */
@RunWith(AndroidJUnit4::class)
class UpdateManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    /** Réponses du faux serveur, par URL : code HTTP et contenu. */
    private val server = mutableMapOf<String, Pair<Int, ByteArray>>()

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val (code, body) = server[request.url.toString()] ?: (404 to ByteArray(0))
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .body(body.toResponseBody())
            .build()
    }.build()

    private fun manager(currentVersionCode: Long = 5) = UpdateManager(context, http, json, UPDATE_URL, currentVersionCode, scope)

    private fun publish(versionCode: Int, apk: ByteArray, sha256: String = apk.sha256(), apkUrl: String = APK_URL) {
        val info = """
            {"versionCode": $versionCode, "versionName": "1.$versionCode.0", "apkUrl": "$apkUrl",
             "sha256": "$sha256", "size": ${apk.size}, "notes": "Nouveautés", "futur": true}
        """.trimIndent()
        server[UPDATE_URL] = 200 to info.toByteArray()
        server[apkUrl] = 200 to apk
    }

    @After
    fun tearDown() {
        scope.cancel()
        File(context.cacheDir, "updates").deleteRecursively()
    }

    @Test
    fun proposeUneVersionPlusRecente() = runBlocking {
        publish(versionCode = 7, apk = ByteArray(10))
        val updates = manager()
        val info = updates.check()
        assertEquals(7, info?.versionCode)
        assertEquals("Nouveautés", info?.notes)
        assertEquals(UpdateState.Available(info!!), updates.state.value)
    }

    @Test
    fun rienAProposerSiDejaAJourOuSansRelease() = runBlocking {
        publish(versionCode = 5, apk = ByteArray(10))
        val updates = manager()
        assertNull(updates.check())
        assertEquals(UpdateState.UpToDate, updates.state.value)

        // Aucune release publiée : GitHub répond 404.
        server.clear()
        assertNull(updates.check())
        assertEquals(UpdateState.UpToDate, updates.state.value)
    }

    @Test
    fun refuseUnUpdateJsonDouteux() = runBlocking {
        publish(versionCode = 7, apk = ByteArray(10), apkUrl = "http://example.com/echo.apk")
        val updates = manager()
        assertNull(updates.check())
        assertEquals(UpdateState.CheckFailed, updates.state.value)

        server[UPDATE_URL] = 500 to ByteArray(0)
        assertNull(updates.check())
        assertEquals(UpdateState.CheckFailed, updates.state.value)
    }

    @Test
    fun rejetteUnTelechargementCorrompu() = runBlocking {
        val apk = ByteArray(200_000) { it.toByte() }
        publish(versionCode = 7, apk = apk, sha256 = ByteArray(200_000).sha256())
        val updates = manager()
        val info = updates.check()!!
        updates.install(info)
        val result = withTimeout(10_000) { updates.state.first { it is UpdateState.Failed || it is UpdateState.Installing } }
        assertEquals(UpdateState.Failed(info, UpdateState.Reason.Integrity), result)
        assertTrue(File(context.cacheDir, "updates").listFiles().isNullOrEmpty())
    }

    @Test
    fun rejetteUnFichierQuiNEstPasUnApk() = runBlocking {
        publish(versionCode = 7, apk = "pas un apk".toByteArray())
        val updates = manager()
        val info = updates.check()!!
        updates.install(info)
        val result = withTimeout(10_000) { updates.state.first { it is UpdateState.Failed || it is UpdateState.Installing } }
        assertEquals(UpdateState.Failed(info, UpdateState.Reason.Integrity), result)
    }

    @Test
    fun nettoieLesApkDejaInstalles() {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val old = File(dir, "echo-3.apk").apply { writeText("x") }
        val current = File(dir, "echo-5.apk").apply { writeText("x") }
        val next = File(dir, "echo-9.apk").apply { writeText("x") }
        val partial = File(dir, "echo-9.apk.part").apply { writeText("x") }
        val stray = File(dir, "autre.bin").apply { writeText("x") }
        manager(currentVersionCode = 5).cleanup()
        assertFalse(old.exists())
        assertFalse(current.exists())
        assertFalse(stray.exists())
        assertTrue(next.exists())
        assertTrue(partial.exists())
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private companion object {
        const val UPDATE_URL = "https://github.test/releases/latest/download/update.json"
        const val APK_URL = "https://github.test/releases/download/v1.7.0/echo-1.7.0.apk"
    }
}
