package io.wxbridge

/**
 * 主 App 侧读取 hook 写下的密码。
 *
 * 数据来源：/data/data/com.tencent.mm/shared_prefs/wxbridge.xml
 * 写入方：io.wxbridge.xposed.KeyBridge（运行在微信进程里）
 */
object WxKeyReader {

    private const val PREFS_XML = "/data/data/com.tencent.mm/shared_prefs/wxbridge.xml"

    data class WxKey(
        val dbPath: String,
        val password: ByteArray,
        val passwordUtf8: String,
        val updatedAt: Long
    ) {
        val dbDir: String get() = WxPaths.dbDirOf(dbPath)
        override fun equals(other: Any?) = other is WxKey && dbPath == other.dbPath
        override fun hashCode() = dbPath.hashCode()
    }

    sealed class Result {
        data class Ok(val key: WxKey) : Result()
        data class Fail(val reason: String) : Result()
    }

    fun read(): Result {
        if (!RootShell.hasRoot()) {
            return Result.Fail("没有 root 权限")
        }
        val xml = RootShell.readText(PREFS_XML)
            ?: return Result.Fail(
                "读不到 $PREFS_XML\n" +
                    "→ 先确认 LSPosed 里本模块已启用且作用域勾了微信\n" +
                    "→ 然后冷启动（强行停止后重开）微信，等它打开聊天列表再来"
            )

        val map = parsePrefs(xml)
        val dbPath = map["db_path"] ?: return Result.Fail("hook 未写入 db_path，可能版本不兼容")
        val hex = map["pwd_hex"] ?: return Result.Fail("hook 未写入 pwd_hex")
        val pwd = hexToBytes(hex) ?: return Result.Fail("密码 hex 解析失败")

        return Result.Ok(
            WxKey(
                dbPath = dbPath,
                password = pwd,
                passwordUtf8 = map["pwd_utf8"] ?: "",
                updatedAt = map["updated_at"]?.toLongOrNull() ?: 0L
            )
        )
    }

    /** Android SharedPreferences XML 是扁平结构，正则足够 */
    private fun parsePrefs(xml: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("<string name=\"([^\"]+)\">([^<]*)</string>")
            .findAll(xml)
            .forEach { out[it.groupValues[1]] = unescapeXml(it.groupValues[2]) }
        Regex("<long name=\"([^\"]+)\" value=\"([^\"]*)\"")
            .findAll(xml)
            .forEach { out[it.groupValues[1]] = it.groupValues[2] }
        Regex("<int name=\"([^\"]+)\" value=\"([^\"]*)\"")
            .findAll(xml)
            .forEach { out[it.groupValues[1]] = it.groupValues[2] }
        return out
    }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) {
                hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
