package io.wxbridge.xposed

import android.app.AndroidAppHelper
import android.content.Context
import org.json.JSONObject

/**
 * 把 hook 到的密码交给主 App。
 *
 * 为什么不用 LocalSocket / ContentProvider / 广播？
 * 因为 Android 11+ 跨 uid 的 abstract socket 在不同 ROM 上时通时不通，
 * 而「微信进程写自己的 shared_prefs + App 用 su 读」这条路零权限问题、100% 可达。
 *
 * 微信 8.0.79 实测：**每个库的密码都不一样**（EnMicroMsg.db / FTS5Index… / WxFileIndex…
 * 各有独立的 7 字节 key）。所以不能只存一个，要按 path 存成 map。
 *
 * 落点：/data/data/com.tencent.mm/shared_prefs/wxbridge.xml
 *       字段 db_keys = {"<db 绝对路径>": "<密码 hex>", ...}
 */
internal object KeyBridge {

    private const val PREFS = "wxbridge"
    private const val KEY_KEYS = "db_keys"
    private const val KEY_MAIN_DB = "main_db"
    private const val KEY_UPDATED_AT = "updated_at"

    @Volatile
    private var ctx: Context? = null

    /** 已写入过的 (path|hex) 组合，避免每次开库都同步写盘 */
    private val written = java.util.Collections.synchronizedSet(HashSet<String>())

    fun attach(c: Context) {
        ctx = c.applicationContext ?: c
    }

    /**
     * 记录哪个库是消息主库（含 message 表的那个）。
     * 微信 8.0.79 没有 EnMicroMsg.db，只能靠实际探测，不能靠文件名猜。
     */
    fun onMainDb(dbPath: String) {
        val app = ctx ?: AndroidAppHelper.currentApplication() ?: return
        runCatching {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MAIN_DB, dbPath)
                .commit()
            xlog("main db recorded: ${dbPath.substringAfterLast('/')}")
        }
    }

    /**
     * 多个库可能在不同线程并发打开，读-改-写 prefs 必须串行，否则会丢更新。
     */
    @Synchronized
    fun onKey(dbPath: String, password: ByteArray) {
        if (password.isEmpty() || password.size > 64) return

        val hex = password.toHex()
        val sig = "$dbPath|$hex"
        if (!written.add(sig)) return

        val app = ctx ?: AndroidAppHelper.currentApplication()
        if (app == null) {
            written.remove(sig)
            xlog("context not ready, drop key for $dbPath")
            return
        }

        runCatching {
            val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val json = runCatching { JSONObject(sp.getString(KEY_KEYS, "{}") ?: "{}") }
                .getOrElse { JSONObject() }
            json.put(dbPath, hex)

            sp.edit()
                .putString(KEY_KEYS, json.toString())
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .commit()

            xlog("key captured: ${dbPath.substringAfterLast('/')} len=${password.size} hex=${hex.take(8)}…")
        }.onFailure {
            written.remove(sig)
            xlog("write prefs failed: $it")
        }
    }
}

internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
    }
    return sb.toString()
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()
