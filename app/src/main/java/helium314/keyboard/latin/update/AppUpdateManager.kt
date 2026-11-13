// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/SUBHOJITPAUL797/DASMO-KEYBOARD/releases/latest"

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val title: String,
        val body: String,
        val apkUrl: String,
        val apkFileName: String,
        val apkSize: Long,
        val isNewer: Boolean
    )

    /**
     * Compares remote version (e.g. "v4.3.3" or "4.3.3") with current version (e.g. "4.3.2").
     * Returns true if remote version is strictly newer.
     */
    fun isNewerVersion(
        remoteTag: String,
        currentVersion: String = BuildConfig.VERSION_NAME
    ): Boolean {
        val cleanRemote = remoteTag.trim().trimStart('v', 'V')
        val cleanCurrent = currentVersion.trim().trimStart('v', 'V')
        if (cleanRemote.isEmpty() || cleanCurrent.isEmpty()) return false
        if (cleanRemote == cleanCurrent) return false

        val remoteParts = cleanRemote.split(Regex("[^0-9]+"))
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(Regex("[^0-9]+"))
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * Fetches latest release information from GitHub API.
     */
    suspend fun fetchLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = openConnectionWithRedirects(GITHUB_LATEST_RELEASE_API)
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return@withContext Result.failure(IOException("No releases found on GitHub repository."))
            }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                return@withContext Result.failure(
                    IOException("GitHub API returned HTTP $responseCode: ${errorStream ?: connection.responseMessage}")
                )
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseBody)
            val tagName = json.optString("tag_name", "").trim()
            val title = json.optString("name", tagName).trim()
            val body = json.optString("body", "").trim()

            val cleanVersion = tagName.trimStart('v', 'V')
            val isNewer = isNewerVersion(tagName, BuildConfig.VERSION_NAME)

            val assets = json.optJSONArray("assets")
            if (assets == null || assets.length() == 0) {
                return@withContext Result.failure(IOException("No assets found in release $tagName."))
            }

            var chosenAsset: JSONObject? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    if (BuildConfig.DEBUG && name.contains("debug", ignoreCase = true)) {
                        chosenAsset = asset
                        break
                    } else if (!BuildConfig.DEBUG && !name.contains("debug", ignoreCase = true)) {
                        chosenAsset = asset
                        break
                    } else if (chosenAsset == null) {
                        chosenAsset = asset
                    }
                }
            }

            if (chosenAsset == null) {
                return@withContext Result.failure(IOException("No APK asset found in release $tagName."))
            }

            val apkUrl = chosenAsset.optString("browser_download_url", "")
            val apkFileName = chosenAsset.optString("name", "DASMO_Keyboard_update.apk")
            val apkSize = chosenAsset.optLong("size", 0L)

            if (apkUrl.isBlank()) {
                return@withContext Result.failure(IOException("Invalid APK download URL in release."))
            }

            Result.success(
                ReleaseInfo(
                    tagName = tagName,
                    versionName = cleanVersion,
                    title = title,
                    body = body,
                    apkUrl = apkUrl,
                    apkFileName = apkFileName,
                    apkSize = apkSize,
                    isNewer = isNewer
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching latest release", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Downloads the APK file to cache/updates/ with progress reporting.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        fileName: String,
        onProgress: (percent: Int, bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        var targetFile: File? = null

        try {
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()

            // Remove previous apk files in cache
            updatesDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".apk", ignoreCase = true)) {
                    file.delete()
                }
            }

            val safeFileName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "DASMO_Keyboard_update.apk" }
            targetFile = File(updatesDir, safeFileName)

            connection = openConnectionWithRedirects(downloadUrl)
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(
                    IOException("Download server returned HTTP $responseCode: ${connection.responseMessage}")
                )
            }

            val totalBytes = connection.contentLength.toLong()
            inputStream = connection.inputStream
            outputStream = FileOutputStream(targetFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var downloadedBytes = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (!coroutineContext.isActive) {
                    targetFile.delete()
                    return@withContext Result.failure(CancellationException("Download cancelled by user"))
                }
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else -1
                withContext(Dispatchers.Main) {
                    onProgress(percent, downloadedBytes, totalBytes)
                }
            }

            outputStream.flush()
            Result.success(targetFile)
        } catch (e: Exception) {
            targetFile?.delete()
            if (e is CancellationException) {
                Result.failure(e)
            } else {
                Log.e(TAG, "Failed to download update APK", e)
                Result.failure(e)
            }
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }

    /**
     * Launches the Package Installer to install the downloaded APK.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = "package:${context.packageName}".toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Toast.makeText(context, R.string.update_permission_required, Toast.LENGTH_LONG).show()
                return false
            }
        }

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.update.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            Toast.makeText(context, "Install error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * User-facing entry point to check for updates and guide the user through downloading & installing.
     */
    fun checkForUpdates(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        val checkingDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.check_for_updates)
            .setMessage(R.string.update_checking)
            .setCancelable(false)
            .create()
        checkingDialog.show()

        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
        scope.launch {
            val result = fetchLatestRelease()
            if (!activity.isFinishing && !activity.isDestroyed) {
                try { checkingDialog.dismiss() } catch (_: Exception) {}
            }

            result.onSuccess { releaseInfo ->
                if (activity.isFinishing || activity.isDestroyed) return@onSuccess

                if (!releaseInfo.isNewer) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_latest, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    showUpdateAvailableDialog(activity, releaseInfo)
                }
            }.onFailure { error ->
                if (activity.isFinishing || activity.isDestroyed) return@onFailure
                val errorMsg = error.localizedMessage ?: "Unknown error"
                AlertDialog.Builder(activity)
                    .setTitle(R.string.check_for_updates)
                    .setMessage(activity.getString(R.string.update_error, errorMsg))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showUpdateAvailableDialog(activity: Activity, releaseInfo: ReleaseInfo) {
        val sizeStr = if (releaseInfo.apkSize > 0) " (${formatFileSize(releaseInfo.apkSize)})" else ""
        val messageBuilder = StringBuilder()
        messageBuilder.append("Current Version: v${BuildConfig.VERSION_NAME}\n")
        messageBuilder.append("New Version: ${releaseInfo.tagName}$sizeStr\n\n")

        if (releaseInfo.body.isNotBlank()) {
            messageBuilder.append("What's New:\n")
            messageBuilder.append(releaseInfo.body.trim())
        }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available, releaseInfo.tagName))
            .setMessage(messageBuilder.toString())
            .setPositiveButton(R.string.update_install) { _, _ ->
                checkPermissionsAndDownload(activity, releaseInfo)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkPermissionsAndDownload(activity: Activity, releaseInfo: ReleaseInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_permission_dialog_title)
                .setMessage(R.string.update_permission_dialog_msg)
                .setPositiveButton(R.string.update_settings_button) { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = "package:${activity.packageName}".toUri()
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                    // Also start download so when user returns it's ready to install
                    startDownloadFlow(activity, releaseInfo)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            startDownloadFlow(activity, releaseInfo)
        }
    }

    private fun startDownloadFlow(activity: Activity, releaseInfo: ReleaseInfo) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val statusText = TextView(activity).apply {
            text = activity.getString(R.string.update_downloading, 0)
            textSize = 15f
            setPadding(0, 0, 0, (8 * activity.resources.displayMetrics.density).toInt())
        }

        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }

        val sizeText = TextView(activity).apply {
            text = ""
            textSize = 12f
            setPadding(0, (6 * activity.resources.displayMetrics.density).toInt(), 0, 0)
        }

        layout.addView(statusText)
        layout.addView(progressBar)
        layout.addView(sizeText)

        var downloadJob: Job? = null

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available, releaseInfo.tagName))
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { d, _ ->
                downloadJob?.cancel()
                d.dismiss()
            }
            .create()

        dialog.show()

        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
        downloadJob = scope.launch {
            val downloadResult = downloadApk(
                context = activity,
                downloadUrl = releaseInfo.apkUrl,
                fileName = releaseInfo.apkFileName
            ) { percent, bytesRead, totalBytes ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    if (percent >= 0) {
                        progressBar.isIndeterminate = false
                        progressBar.progress = percent
                        statusText.text = activity.getString(R.string.update_downloading, percent)
                        sizeText.text = "${formatFileSize(bytesRead)} / ${formatFileSize(totalBytes)}"
                    } else {
                        progressBar.isIndeterminate = true
                        statusText.text = activity.getString(R.string.update_downloading, 0).replace(" 0%", "…")
                        sizeText.text = formatFileSize(bytesRead)
                    }
                }
            }

            if (!activity.isFinishing && !activity.isDestroyed) {
                try { dialog.dismiss() } catch (_: Exception) {}
            }

            downloadResult.onSuccess { apkFile ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    installApk(activity, apkFile)
                }
            }.onFailure { error ->
                if (error !is CancellationException && !activity.isFinishing && !activity.isDestroyed) {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.check_for_updates)
                        .setMessage(activity.getString(R.string.update_download_failed, error.localizedMessage ?: "Unknown error"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun openConnectionWithRedirects(initialUrl: String, timeoutMs: Int = 15000): HttpURLConnection {
        var urlStr = initialUrl
        var redirects = 0
        while (redirects < 6) {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "DASMO-Keyboard/${BuildConfig.VERSION_NAME}")

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308
            ) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (newUrl != null) {
                    urlStr = newUrl
                    redirects++
                    continue
                }
            }
            return connection
        }
        throw IOException("Too many redirects ($redirects)")
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var digitGroups = 0
        var b = bytes.toDouble()
        while (b >= 1024 && digitGroups < units.size - 1) {
            b /= 1024
            digitGroups++
        }
        return String.format(Locale.US, "%.1f %s", b, units[digitGroups])
    }
}
