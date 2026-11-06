package io.wxbridge.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 从 WCDB 的 openDatabase 里截获 (path, password)。
 *
 * WCDB 是腾讯开源的独立 library（com.tencent.wcdb 包），
 * 不参与微信主包的混淆规则，所以类名和签名在 8.x 各版本里基本稳定。
 * 这是整个方案里最脆弱、也最值得单独隔离的一个点。
 */
internal object WcdbKeyHook {

    /**
     * 主库实例（com.tencent.wcdb.database.SQLiteDatabase）。
     * 自动回复在微信进程内直接查它 —— 同一进程、已是解密状态，不用密码也不用复制。
     */
    @Volatile
    var mainDb: Any? = null
        private set

    /** 主库绝对路径，用来推 MicroMsg/<md5>/ 目录（表情包所在位置） */
    @Volatile
    var mainDbPath: String? = null
        private set

    /** (类名, 方法名, 是否要求返回值非空) */
    private val targets = listOf(
        Triple("com.tencent.wcdb.database.SQLiteDatabase", "openDatabase", true),
        // 底层连接，兜底：少数路径绕过 openDatabase 直接建连接
        Triple("com.tencent.wcdb.database.SQLiteConnection", "open", false)
    )

    fun install(cl: ClassLoader) {
        var hooked = 0
        for ((cls, method, needResult) in targets) {
            val c = runCatching { XposedHelpers.findClass(cls, cl) }.getOrNull()
            if (c == null) {
                xlog("class not found: $cls (skip)")
                continue
            }
            runCatching {
                XposedBridge.hookAllMethods(c, method, makeCallback(needResult))
                hooked++
                xlog("hooked $cls#$method")
            }.onFailure { xlog("hook $cls#$method failed: $it") }
        }
        if (hooked == 0) {
            xlog("WARNING: no WCDB hook installed — 微信可能改了库结构或本模块未启用")
        }
    }

    private fun makeCallback(needResult: Boolean) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // openDatabase 成功才写；失败时 result 为 null
            if (needResult && param.result == null) return

            val args = param.args ?: return
            var path: String? = null
            var pwd: ByteArray? = null

            for (a in args) {
                when (a) {
                    is String -> if (looksLikeDbPath(a)) path = a
                    // 密码是 byte[]，长度很短；用它排除掉其他 byte[] 参数（如 BLOB）
                    is ByteArray -> if (a.isNotEmpty() && a.size <= 64) pwd = a
                }
            }

            val p = path ?: return

            // 主库实例留给自动回复用
            if (needResult && p.endsWith("EnMicroMsg.db")) {
                param.result?.let {
                    mainDb = it
                    mainDbPath = p
                }
            }

            val k = pwd ?: return
            runCatching { KeyBridge.onKey(p, k) }
        }
    }

    private fun looksLikeDbPath(s: String): Boolean =
        s.endsWith(".db") && (s.startsWith("/") || s.contains("MicroMsg"))
}
