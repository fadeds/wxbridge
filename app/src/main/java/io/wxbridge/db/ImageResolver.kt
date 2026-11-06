package io.wxbridge.db

import android.util.Log
import io.wxbridge.RootShell
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * 微信图片定位。
 *
 * 这里必须诚实说明：微信图片的落盘命名在不同版本间变过，没有单一可靠映射。
 * 所以采用三级策略，逐级降级：
 *   1. imageinfo2.db 的 ImgInfo2.bigImgPath —— 最准确
 *   2. 由文件名（32 位 md5）推出 image2/<前两位>/ 目录
 *   3. 扫描 image2/ 建 mtime 索引，按消息时间 ±15s 猜
 *
 * 第 3 级是启发式，会错配。所以每次命中都会打日志，方便你核对。
 */
class ImageResolver(
    private val imageDb: SQLiteDatabase?,
    private val dbDir: String,
    private val workDir: File
) {
    private val imageOutDir = File(workDir, "images").apply { mkdirs() }

    @Volatile
    private var mtimeIndex: List<Pair<Long, String>>? = null

    /** 返回已复制到 app 私有目录的图片文件，调用方可直接读 */
    fun materialize(msg: WxMessage): File? {
        if (msg.type != MsgType.IMAGE) return null

        val abs = resolvePath(msg) ?: return null
        val local = File(imageOutDir, File(abs).name)
        if (local.exists() && local.length() > 0) return local
        if (!RootShell.copyToAppData(abs, local)) {
            // 可能是 .t.jpg 缩略图之外的路径，退回缩略图
            val thumb = abs.replace(Regex("\\.(jpg|png|gif)$"), ".t.jpg")
            if (thumb != abs && RootShell.copyToAppData(thumb, local)) return local
            return null
        }
        return local
    }

    fun resolvePath(msg: WxMessage): String? {
        fromImgInfo2(msg.msgSvrId)?.let { return it }
        fromImgPathField(msg.imgPath)?.let { return it }
        return guessByTime(msg.createTime)
    }

    // ---- 1. ImgInfo2 ----

    private fun fromImgInfo2(msgSvrId: Long): String? {
        val db = imageDb ?: return null
        if (msgSvrId == 0L) return null
        var cur: android.database.Cursor? = null
        return try {
            cur = db.rawQuery(
                "SELECT bigImgPath, thumbImgPath FROM ImgInfo2 WHERE msgSvrId = ? LIMIT 1",
                arrayOf<Any>(msgSvrId)
            )
            if (!cur.moveToFirst()) return null
            val big = cur.getString(0)
            val thumb = cur.getString(1)
            normalize(big) ?: normalize(thumb)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { cur?.close() }
        }
    }

    // ---- 2. message.imgPath ----

    private fun fromImgPathField(imgPath: String?): String? {
        if (imgPath.isNullOrBlank()) return null
        return normalize(imgPath)
    }

    private fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // 微信存的是缩略图句柄，拿不到大图路径
        if (raw.startsWith("THUMBNAIL_DIRPATH://") || raw.startsWith("THUMBNAIL://")) return null

        val candidate = when {
            raw.startsWith("/image2/") -> "$dbDir$raw"
            raw.startsWith("image2/") -> "$dbDir/$raw"
            raw.startsWith("/") -> raw
            raw.endsWith(".jpg") || raw.endsWith(".png") || raw.endsWith(".gif") -> {
                // 纯文件名：image2/<md5 前两位>/<name>
                val name = File(raw).name
                val stem = name.substringBeforeLast('.')
                if (stem.length < 32) return null
                "$dbDir/image2/${stem.take(2)}/$name"
            }
            else -> return null
        }
        // 注意：这条检查必须走 root —— app 进程没有权限 stat 微信私有目录，
        // File(candidate).exists() 永远是 false。
        val exists = RootShell.exec("test -f '$candidate' && echo 1 || echo 0", 8).out.trim() == "1"
        return if (exists) candidate else null
    }

    // ---- 3. 时间兜底 ----

    private fun guessByTime(createTime: Long): String? {
        val index = mtimeIndex ?: buildIndex().also { mtimeIndex = it }
        if (index.isEmpty()) return null

        // 只要大图，缩略图（.t.jpg）没什么信息量
        val candidates = index.filterNot { it.second.endsWith(".t.jpg") }
        if (candidates.isEmpty()) return null

        val hit = candidates.minByOrNull { kotlin.math.abs(it.first - createTime) } ?: return null
        val delta = kotlin.math.abs(hit.first - createTime)
        if (delta > 15) return null
        Log.d(TAG, "guess image by mtime: ${hit.second} (Δ${delta}s)")
        return hit.second
    }

    private fun buildIndex(): List<Pair<Long, String>> {
        val root = "$dbDir/image2"
        val cmd = "find '$root' -type f \\( -name '*.jpg' -o -name '*.png' -o -name '*.gif' \\) " +
            "-exec stat -c '%Y %n' {} + 2>/dev/null"
        val r = RootShell.exec(cmd, 90)
        return r.out.lineSequence().mapNotNull { line ->
            val sp = line.indexOf(' ')
            if (sp <= 0) return@mapNotNull null
            val t = line.substring(0, sp).toLongOrNull() ?: return@mapNotNull null
            t to line.substring(sp + 1).trim()
        }.sortedBy { it.first }.toList().also {
            Log.i(TAG, "image index built: ${it.size} files")
        }
    }

    private companion object {
        const val TAG = "WxBridgeImage"
    }
}
