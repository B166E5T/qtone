package com.qtone.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads an APK from a URL into the app's cache dir, then asks Android to
 * install it. The install step is a separate process — the system package
 * installer takes over once we hand it our APK URI.
 *
 * Flow:
 *   1. caller invokes [downloadAndInstall] with the apkUrl from a manifest.
 *   2. We stream the APK to ${cacheDir}/updates/qtone-update.apk, updating
 *      [progress] as bytes flow in. UI observes the StateFlow for the
 *      progress bar.
 *   3. When the download finishes successfully, we launch the installer via
 *      [launchInstaller]. The user sees Android's standard install dialog
 *      ("Do you want to install an update to this application?"). On Android
 *      8+ first-time users also see the "Allow from this source" toggle —
 *      we redirect them to Settings if not yet granted.
 *   4. After the user confirms, Android stops Qtone, installs the new APK,
 *      and either restarts the app or leaves the user at the home screen
 *      depending on device.
 *
 * Threading: download runs on Dispatchers.IO. The progress StateFlow is
 * thread-safe and can be collected from any dispatcher (typically Compose's
 * main thread via collectAsState).
 */
object UpdateInstaller {

    /**
     * Download progress as a fraction between 0.0 and 1.0.
     * -1f signals failure; null / 0f means "not started yet".
     */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    /**
     * Whether a download is currently in flight. Used by UI to disable
     * the "Update Now" button so the user can't kick off a second download.
     */
    private val _inProgress = MutableStateFlow(false)
    val inProgress: StateFlow<Boolean> = _inProgress

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Download the APK at [apkUrl] and trigger the system installer.
     *
     * Returns true on successful handoff to the installer (which is asynchronous
     * from the app's point of view — the actual install happens in another
     * process and we lose execution context when it succeeds).
     *
     * Returns false if the download itself failed.
     */
    suspend fun downloadAndInstall(context: Context, apkUrl: String): Boolean {
        if (_inProgress.value) return false
        _inProgress.value = true
        _progress.value = 0f

        return try {
            val apkFile = withContext(Dispatchers.IO) { downloadApk(context, apkUrl) }
                ?: return false.also { _progress.value = -1f }

            launchInstaller(context, apkFile)
            true
        } finally {
            _inProgress.value = false
        }
    }

    /**
     * Stream the APK from [apkUrl] to the cache dir, updating [_progress] as
     * we go. Returns the downloaded File on success, null on any failure.
     */
    private fun downloadApk(context: Context, apkUrl: String): File? {
        return try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updatesDir, "qtone-update.apk")
            // Delete any previous partial / stale download. This avoids the
            // installer rejecting the file with a "corrupt APK" message if
            // the prior download was interrupted.
            if (apkFile.exists()) apkFile.delete()

            val request = Request.Builder().url(apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val total = body.contentLength().takeIf { it > 0L } ?: -1L

                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                _progress.value = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                    }
                }
            }

            _progress.value = 1f
            apkFile
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Hand the downloaded APK to the system package installer.
     *
     * Android 8+ requires the calling app to hold REQUEST_INSTALL_PACKAGES
     * AND to have been granted "Install unknown apps" by the user. We probe
     * with [PackageManager.canRequestPackageInstalls] and if blocked, redirect
     * to the per-app permission Settings page. From there one tap toggles the
     * permission on, and the user can press Back to return — the next call
     * to launchInstaller succeeds.
     */
    private fun launchInstaller(context: Context, apkFile: File) {
        // Build the content:// URI via FileProvider. The authority must match
        // the one declared in AndroidManifest.xml.
        val authority = "${context.packageName}.fileprovider"
        val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // First-time: send the user to the per-app "Install unknown apps"
            // toggle. They flip it on, press Back, then re-tap Update.
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }
}
