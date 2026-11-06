package io.wxbridge.xposed

import android.content.Context
import de.robv.android.xposed.AndroidAppHelper

/**
 * 把 hook 到的密码交给主 App。
 *
 * 为什么不用 LocalSocket / ContentProvider / 广播？
 * 因为 Android 11+ 跨 uid 的 abstract socket 在不同 ROM 上时通时不通，
 * 而「微信进程写自己的 shared_prefs + App 用 su 读」这条路零权限问题、100% 可达。
 *
 * 落点：/data/data/com.tencent.mm/shared_prefs/wxbridge.xml
 * 主 App 侧对应实现见 io.wxbridge.WxKeyReader
 */
internal object KeyBridge {

    private const val PREFS = "wxbridge"
    private const val KEY_DB_PATH = "db_path"
    private const val KEY_PWD_HEX = "pwd_hex"
    private const val KEY_PWD_UTF8 = "pwd_utf8"
    private const val KEY_UPDATED_AT = "updated_at"

    @Volatile
    private var ctx: Context? = null

    /** 已写入过的 (path|hex) 组合，避免每次 openDatabase 都同步写盘 */
    private val written = java.util.Collections.synchronizedSet(HashSet<String>())

    fun attach(c: Context) {
        ctx = c.applicationContext ?: c
    }

    fun onKey(dbPath: String, password: ByteArray) {
        if (password.isEmpty() || password.size > 64) return

        val hex = password.toHex()
        val sig = "$dbPath|$hex"
        if (!written.add(sig)) return

        val app = ctx ?: AndroidAppHelper.currentApplication()
        if (app == null) {
            // Context 还没到，撤销去重标记，下次重试
            written.remove(sig)
            xlog("context not ready, drop key for $dbPath")
            return
        }

        runCatching {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DB_PATH, dbPath)
                .putString(KEY_PWD_HEX, hex)
                .putString(KEY_PWD_UTF8, String(password, Charsets.UTF_8))
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .commit()
            xlog("key captured: $dbPath len=${password.size} hex=${hex.take(8)}…")
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
