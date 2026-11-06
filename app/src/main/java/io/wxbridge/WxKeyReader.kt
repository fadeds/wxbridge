package io.wxbridge

import org.json.JSONObject

/**
 * 主 App 侧读取 hook 写下的密码。
 *
 * 数据来源：/data/data/com.tencent.mm/shared_prefs/wxbridge.xml
 * 写入方：io.wxbridge.xposed.KeyBridge（运行在微信进程里）
 *
 * 微信 8.0.79 实测：每个库的密码都不一样，所以这里存的是
 * path → 密码 的映射，不再是一个全局密码。
 */
object WxKeyReader {

    private const val PREFS_XML = "/data/data/com.tencent.mm/shared_prefs/wxbridge.xml"

    data class WxKey(
        /** 主库 EnMicroMsg.db 的绝对路径 */
        val dbPath: String,
        /** 主库密码 */
        val password: ByteArray,
        /** 所有已捕获的库：绝对路径 → 密码 */
        val keys: Map<String, ByteArray>
    ) {
        val dbDir: String get() = WxPaths.dbDirOf(dbPath)
        val dbName: String get() = dbPath.substringAfterLast('/')

        /**
         * 按路径取密码。取不到时按文件名再匹配一次
         * （微信在 /data/user/0 和 /data/data 两种前缀下指向同一个文件）。
         */
        fun passwordFor(path: String): ByteArray {
            keys[path]?.let { return it }
            val name = path.substringAfterLast('/')
            keys.entries.firstOrNull { it.key.substringAfterLast('/') == name }?.let { return it.value }
            return password
        }
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
                    "→ 确认 LSPosed 里模块已启用、作用域勾了微信\n" +
                    "→ 然后冷启动（强行停止后重开）微信"
            )

        val map = parsePrefs(xml)
        val keysJson = map["db_keys"]
            ?: return Result.Fail(
                "模块还没抓到任何密码。\n" +
                    "→ 冷启动微信后，先在微信里点开任意一个聊天，再回来读"
            )

        val keys = parseKeys(keysJson)
        if (keys.isEmpty()) {
            return Result.Fail("db_keys 为空，模块可能没抓到密码")
        }

        // 模块会探测出哪个库含 message 表并记下来，优先用这个
        val mainPath = map["main_db"]
        val main = when {
            mainPath != null && keys.containsKey(mainPath) -> mainPath to keys.getValue(mainPath)
            // 兼容老数据
            else -> keys.entries.firstOrNull { it.key.endsWith("EnMicroMsg.db") }
        } ?: return Result.Fail(
            "已抓到 ${keys.size} 个库的密码，但还没定位到消息主库。\n" +
                "→ 在微信里点开任意一个聊天（主库懒加载），然后再回来\n" +
                "→ 已抓到：${keys.keys.joinToString { it.substringAfterLast('/') }.take(150)}"
        )

        return Result.Ok(WxKey(main.first, main.second, keys))
    }

    private fun parseKeys(json: String): Map<String, ByteArray> = runCatching {
        val o = JSONObject(json)
        o.keys().asSequence()
            .mapNotNull { k -> hexToBytes(o.optString(k))?.let { k to it } }
            .toMap()
    }.getOrDefault(emptyMap())

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
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) {
                hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
