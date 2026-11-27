package com.vasik96.spojeuk

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Aktualizace přímo v aplikaci: stáhne APK z GitHub releasu do cache, ověří ho (balíček, verze, stejný podpisový
 * certifikát jako nainstalovaná aplikace) a otevře systémový instalátor. Povolení "instalovat neznámé aplikace"
 * si instalátor vyžádá sám. Stahování běží mimo obrazovku (zavření dialogu ho nezruší).
 */
internal object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val DIR = "updates"
    private const val PROGRESS_EVERY_MS = 50L
    private const val INSTALL_DELAY_MS = 450L

    sealed interface State {
        data object Idle : State
        /** [progress] 0..1, nebo null když server neposlal velikost ([totalBytes] pak 0). */
        data class Downloading(
            val version: String,
            val progress: Float?,
            val doneBytes: Long = 0L,
            val totalBytes: Long = 0L,
        ) : State
        data class Ready(val version: String, val file: File) : State
        data class Failed(val version: String, val reason: Reason, val release: AppRelease) : State
    }

    enum class Reason {
        /** Síť / server. */
        DOWNLOAD,
        /** Stažený soubor není APK této aplikace v očekávané verzi. */
        INVALID,
        /** Jiný podpisový klíč než nainstalovaná aplikace: přes ni nejde nainstalovat (nutná přeinstalace). */
        SIGNATURE,
        /**
         * Nainstalovaný build je novější (versionCode = čas sestavení, např. vlastní build) => systém by odmítl
         * downgrade jen obecným "Aplikace nebyla nainstalována".
         */
        OLDER,
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Stav stahování právě této verze; jiná verze = [State.Idle] (nic neprobíhá). */
    fun stateFor(state: State, release: AppRelease): State {
        val v = when (state) {
            is State.Downloading -> state.version
            is State.Ready -> state.version
            is State.Failed -> state.version
            State.Idle -> return State.Idle
        }
        return if (v == release.version) state else State.Idle
    }

    /** Jde stáhnout přímo (release má APK); jinak jen odkaz na stránku releasu. */
    fun canDownload(release: AppRelease): Boolean = release.apkUrl != null

    /** Stáhne (pokud už není staženo) a otevře instalaci. Opakované volání během stahování nic nedělá. */
    fun downloadAndInstall(context: Context, release: AppRelease) {
        val app = context.applicationContext
        val url = release.apkUrl ?: return
        when (val s = _state.value) {
            is State.Downloading -> if (s.version == release.version) return
            is State.Ready -> if (s.version == release.version && s.file.exists()) { install(app, s.file); return }
            else -> Unit
        }
        job?.cancel()
        _state.value = State.Downloading(release.version, 0f, 0L, release.apkSize)
        job = scope.launch {
            val result = runCatching { download(app, release, url) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _state.value = result.fold(
                onSuccess = { file ->
                    val bad = verify(app, file, release.version)
                    if (bad == null) State.Ready(release.version, file)
                    else { file.delete(); State.Failed(release.version, bad, release) }
                },
                onFailure = {
                    Log.w(TAG, "stažení ${release.version} selhalo: ${it.message}")
                    State.Failed(release.version, Reason.DOWNLOAD, release)
                }
            )
            (_state.value as? State.Ready)?.let {
                delay(INSTALL_DELAY_MS)   // pruh v dialogu plynule dojede do konce, pak systémová instalace
                install(app, it.file)
            }
        }
    }

    private suspend fun download(ctx: Context, release: AppRelease, url: String): File {
        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        val target = File(dir, "spojeuk-${release.version}.apk")
        // staré verze pryč (zabírají ~30 MB)
        dir.listFiles()?.forEach { if (it != target) it.delete() }
        if (target.exists() && release.apkSize > 0 && target.length() == release.apkSize) return target

        val part = File(dir, target.name + ".part")
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize.takeIf { it > 0 }
            var done = 0L
            var lastEmit = 0L
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // průběh často (plynulý pruh), ale ne pro každý blok: jednou za PROGRESS_EVERY_MS
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmit >= PROGRESS_EVERY_MS || done == total) {
                            lastEmit = now
                            _state.value = State.Downloading(release.version, total?.let { done.toFloat() / it }, done, total ?: 0L)
                        }
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            if (total != null && done != total) error("neúplné stažení ($done / $total B)")
        }
        if (!part.renameTo(target)) error("nejde přejmenovat ${part.name}")
        return target
    }

    /** null = v pořádku, jinak důvod, proč APK nejde nainstalovat přes tuto aplikaci. */
    private fun verify(ctx: Context, file: File, version: String): Reason? {
        val pm = ctx.packageManager
        val info = runCatching {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        }.getOrNull() ?: return Reason.INVALID
        if (info.packageName != ctx.packageName) return Reason.INVALID
        if (UpdateChecker.compareVersions(info.versionName.orEmpty(), version) != 0) return Reason.INVALID
        val installed = runCatching { pm.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)
        if (info.longVersionCode <= installed) return Reason.OLDER
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        if (signers.isEmpty()) return Reason.INVALID
        val same = signers.all { pm.hasSigningCertificate(ctx.packageName, it.toByteArray(), PackageManager.CERT_INPUT_RAW_X509) }
        return if (same) null else Reason.SIGNATURE
    }

    /** Systémový instalátor; když appka zrovna není v popředí, systém ho nespustí (pak znovu z dialogu / nastavení). */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "instalátor nejde otevřít: ${it.message}") }
    }
}
