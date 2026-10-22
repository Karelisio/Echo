package com.karelisio.echo.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.karelisio.echo.util.await
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
private const val MAX_APK_BYTES = 100L * 1024 * 1024

/** Contenu de update.json, publié par la CI avec chaque release GitHub. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    /** Empreinte SHA-256 de l'APK, en hexadécimal. */
    val sha256: String,
    val size: Long,
    val notes: String = "",
) {
    // Pas de companion privé ici : le plugin de sérialisation y range serializer().
    val isValid: Boolean
        get() = versionCode > 0 && versionName.isNotBlank() && apkUrl.startsWith("https://") &&
            SHA256_REGEX.matches(sha256) && size in 1..MAX_APK_BYTES
}

sealed interface UpdateState {
    /** Pas encore de vérification depuis le lancement. */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object CheckFailed : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState

    /** APK confié à Android, qui demande confirmation puis installe (l'appli est alors relancée). */
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val info: UpdateInfo, val reason: Reason) : UpdateState

    enum class Reason { Download, Integrity, Conflict, Storage, Blocked, Install }
}

/** Version proposée à l'installation (carte de mise à jour), sinon `null`. */
val UpdateState.offer: UpdateInfo?
    get() = when (this) {
        is UpdateState.Available -> info
        is UpdateState.Downloading -> info
        is UpdateState.Installing -> info
        is UpdateState.Failed -> info
        else -> null
    }

/**
 * Mises à jour hors Play Store : update.json de la dernière release GitHub, téléchargement de
 * l'APK (taille et SHA-256 vérifiés), puis installation par PackageInstaller. Android refuse de
 * toute façon une mise à jour qui ne serait pas signée avec la même clé que l'appli installée.
 */
class UpdateManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val json: Json,
    updateUrl: String,
    private val currentVersionCode: Long,
    private val scope: CoroutineScope,
) {

    private val updateUrl = updateUrl.trim()

    val isEnabled: Boolean get() = updateUrl.startsWith("https://")

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _confirmation = MutableStateFlow<Intent?>(null)

    /** Fenêtre de confirmation d'Android, à ouvrir dès que l'appli est au premier plan. */
    val confirmation: StateFlow<Intent?> = _confirmation.asStateFlow()

    private val updatesDir get() = File(context.cacheDir, "updates")

    @Volatile private var lastCheckAt = 0L

    @Volatile private var installing: UpdateInfo? = null
    private var installJob: Job? = null

    /** Cherche une version plus récente ; ne touche pas à un téléchargement ou une installation en cours. */
    suspend fun check(): UpdateInfo? {
        if (!isEnabled) return null
        lastCheckAt = System.currentTimeMillis()
        _state.update { if (it.isBusy) it else UpdateState.Checking }
        val info = try {
            fetchInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Vérification des mises à jour impossible", e)
            _state.update { if (it == UpdateState.Checking) UpdateState.CheckFailed else it }
            return null
        }
        val newer = info?.takeIf { it.versionCode > currentVersionCode }
        _state.update {
            when {
                it.isBusy -> it
                newer != null -> UpdateState.Available(newer)
                else -> UpdateState.UpToDate
            }
        }
        return newer
    }

    fun checkNow() {
        scope.launch { check() }
    }

    /** Vérification à l'ouverture de l'appli, au plus toutes les 6 h. */
    fun checkIfStale() {
        if (isEnabled && System.currentTimeMillis() - lastCheckAt >= STALE_AFTER_MS) checkNow()
    }

    /** Autorisation « Installer des applis inconnues » accordée à Écho. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun install(info: UpdateInfo) {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            _state.value = UpdateState.Downloading(info, 0f)
            try {
                val apk = download(info)
                verifyArchive(apk, info)
                installing = info
                _state.value = UpdateState.Installing(info)
                commit(apk)
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(info)
                throw e
            } catch (e: UpdateException) {
                Log.w(TAG, "Mise à jour impossible : ${e.reason}", e)
                _state.value = UpdateState.Failed(info, e.reason)
            } catch (e: IOException) {
                Log.w(TAG, "Téléchargement de la mise à jour impossible", e)
                _state.value = UpdateState.Failed(info, UpdateState.Reason.Download)
            } catch (e: Exception) {
                Log.w(TAG, "Installation de la mise à jour impossible", e)
                _state.value = UpdateState.Failed(info, UpdateState.Reason.Install)
            }
        }
    }

    /** Statut renvoyé par PackageInstaller (voir [InstallResultReceiver]). */
    fun onInstallStatus(status: Int, confirmation: Intent?) {
        val info = installing
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                if (confirmation != null) _confirmation.value = confirmation else fail(info, UpdateState.Reason.Install)
            PackageInstaller.STATUS_SUCCESS -> {
                installing = null
                _state.value = UpdateState.UpToDate
            }
            // Refusée dans la fenêtre d'Android : on peut la reproposer telle quelle.
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = info?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            // Signature différente de celle de l'appli installée (ou paquet en conflit).
            PackageInstaller.STATUS_FAILURE_CONFLICT -> fail(info, UpdateState.Reason.Conflict)
            PackageInstaller.STATUS_FAILURE_STORAGE -> fail(info, UpdateState.Reason.Storage)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> fail(info, UpdateState.Reason.Blocked)
            else -> fail(info, UpdateState.Reason.Install)
        }
    }

    fun onConfirmationShown() {
        _confirmation.value = null
    }

    /** Supprime du cache les APK déjà installés (ou plus anciens). */
    fun cleanup() {
        updatesDir.listFiles()?.forEach { file ->
            val code = file.name.removePrefix("echo-").substringBefore('.').toLongOrNull()
            if (code == null || code <= currentVersionCode) file.delete()
        }
    }

    private fun fail(info: UpdateInfo?, reason: UpdateState.Reason) {
        _state.value = if (info != null) UpdateState.Failed(info, reason) else UpdateState.Idle
    }

    private suspend fun fetchInfo(): UpdateInfo? {
        val request = Request.Builder().url(updateUrl).header("Cache-Control", "no-cache").build()
        val response = http.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use {
                // Aucune release publiée pour l'instant : rien à proposer.
                if (it.code == 404) return@withContext null
                if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                val info = json.decodeFromString<UpdateInfo>(it.body.string())
                if (!info.isValid) throw IOException("update.json invalide")
                info
            }
        }
    }

    private suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = updatesDir.apply { mkdirs() }
        val apk = File(dir, "echo-${info.versionCode}.apk")
        val expected = info.sha256.lowercase()
        // Déjà téléchargé (nouvel essai après une installation annulée) : inutile de recommencer.
        if (apk.length() == info.size && apk.sha256() == expected) return@withContext apk
        dir.listFiles()?.forEach { it.delete() }

        val partial = File(dir, "${apk.name}.part")
        val response = http.newCall(Request.Builder().url(info.apkUrl).build()).await()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var percent = 0
            it.body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        received += read
                        if (received > info.size) throw UpdateException(UpdateState.Reason.Integrity)
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        val current = (received * 100 / info.size).toInt()
                        if (current != percent) {
                            percent = current
                            _state.value = UpdateState.Downloading(info, current / 100f)
                        }
                    }
                }
            }
            if (received != info.size || digest.digest().toHex() != expected) {
                partial.delete()
                throw UpdateException(UpdateState.Reason.Integrity)
            }
        }
        if (!partial.renameTo(apk)) throw IOException("Impossible d'enregistrer l'APK")
        apk
    }

    /** Le fichier est bien une version d'Écho, celle annoncée par update.json. */
    private fun verifyArchive(apk: File, info: UpdateInfo) {
        val packageManager = context.packageManager
        val archive = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apk.path, 0)
        }
        if (archive == null || archive.packageName != context.packageName ||
            PackageInfoCompat.getLongVersionCode(archive) != info.versionCode.toLong()
        ) {
            throw UpdateException(UpdateState.Reason.Integrity)
        }
    }

    private suspend fun commit(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("echo.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(InstallResultReceiver.statusReceiver(context, sessionId))
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw UpdateException(UpdateState.Reason.Install, e)
        }
    }

    private val UpdateState.isBusy get() = this is UpdateState.Downloading || this is UpdateState.Installing

    private class UpdateException(val reason: UpdateState.Reason, cause: Throwable? = null) : Exception(reason.name, cause)

    private companion object {
        const val TAG = "EchoUpdate"
        const val STALE_AFTER_MS = 6 * 3600_000L
        const val BUFFER_SIZE = 64 * 1024

        fun File.sha256(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
