package io.wxbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 GitHub Releases 检查更新。
 *
 * 版本比较用 versionName 的语义版本号，不用 versionCode —— Release 里只有 tag，
 * 拿不到 versionCode。本地从 main 分支构建出来的是 `0.0.0-<sha>`，远端发了正式版
 * 就会被判定为「有更新」，这正是我们想要的。
 *
 * 仓库是私有的话需要 GitHub token；公开仓库留空即可（未认证限 60 次/小时，够用）。
 */
object UpdateChecker {

    /** 改仓库地址就改这里 */
    private const val REPO = "fadeds/wxbridge"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    data class Release(
        val tag: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val sizeBytes: Long
    )

    sealed class Result {
        data class Newer(val release: Release) : Result()
        data class UpToDate(val current: String) : Result()
        data class Fail(val reason: String) : Result()
    }

    suspend fun check(currentVersion: String, token: String?): Result = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                // GitHub API 强制要求 UA，缺了直接 403
                setRequestProperty("User-Agent", "wxbridge-updater")
                token?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            if (code == 404) return@withContext Result.Fail("仓库还没有发布 Release（$REPO）")
            if (code == 401 || code == 403) {
                return@withContext Result.Fail("HTTP $code：私有仓库需要填 GitHub token，或触发了限流")
            }
            if (code !in 200..299) return@withContext Result.Fail("HTTP $code: ${body.take(200)}")

            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v").trim()
            if (tag.isBlank()) return@withContext Result.Fail("Release 里没有 tag_name")

            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var apkName = ""
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val n = a.optString("name")
                    if (!n.endsWith(".apk", ignoreCase = true)) continue
                    apkUrl = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                    apkName = n
                    size = a.optLong("size")
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) {
                return@withContext Result.Fail("这个 Release 里没有 .apk 附件")
            }

            val release = Release(
                tag = tag,
                title = json.optString("name").ifBlank { "v$tag" },
                notes = json.optString("body").take(3000),
                apkUrl = apkUrl,
                apkName = apkName,
                sizeBytes = size
            )

            if (isNewer(tag, currentVersion)) Result.Newer(release)
            else Result.UpToDate(currentVersion)
        } catch (t: Throwable) {
            Result.Fail("请求失败：${t.javaClass.simpleName} ${t.message ?: ""}")
        }
    }

    /** 语义版本比较：1.10.0 > 1.9.2，0.0.0-abc 视为 0.0.0 */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parse(remote) ?: return false
        val l = parse(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parse(v: String): List<Int>? {
        val core = v.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        val parts = core.split('.').mapNotNull { it.toIntOrNull() }
        return parts.ifEmpty { null }
    }

    /** 下载到 cacheDir/update/，返回文件 */
    suspend fun download(
        release: Release,
        ctx: Context,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
            val dest = File(dir, release.apkName.ifBlank { "wxbridge-${release.tag}.apk" })
            if (dest.exists()) dest.delete()

            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "wxbridge-updater")
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@withContext null
            }

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            var read = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            conn.disconnect()
            if (dest.length() > 0) dest else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 拉起系统安装器。
     * Android 8+ 要求「安装未知应用」权限，没开就先引导去设置页。
     */
    fun install(ctx: Context, apk: File): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${ctx.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return "需要先允许「安装未知应用」，已跳到设置页，授权后再点一次"
        }

        return runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
            "已拉起安装器"
        }.getOrElse { "拉起安装器失败：${it.message}" }
    }
}
