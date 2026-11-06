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

    /** 依次尝试多个路径，返回第一个能读到的内容 */
    private fun readFirst(paths: List<String>): String? {
        for (p in paths) {
            val t = RootShell.readText(p)
            if (t != null && t.isNotBlank()) return t
        }
        return null
    }

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

    /**
     * @param workDir   如果模块没能定位主库，就用它做解密副本目录、本地探测一遍。
     *                  传 null 表示不探测，只在模块已定位成功时返回。
     * @param cacheFile App 侧缓存「上次探测到的主库」，避免每次读密钥都重扫一遍库目录
     * @param onLog     探测过程的进度输出
     */
    fun read(
        workDir: java.io.File? = null,
        cacheFile: java.io.File? = null,
        onLog: (String) -> Unit = {}
    ): Result {
        if (!RootShell.hasRoot()) {
            return Result.Fail("没有 root 权限")
        }

        // 微信可能装在「手机分身」里（user 999），候选根目录按实际用户枚举
        val roots = RootShell.wxCandidates()

        // ① 首选模块直接写的 keys.json。
        //    走 SharedPreferences 有过一次「日志说写成功、文件其实不存在」的教训，
        //    普通文件写入失败会抛异常，不会静默。
        var keysJson: String? = null
        var mainHint: String? = null
        var source = ""
        for (r in roots) {
            val t = RootShell.readText("$r/files/wxbridge/keys.json")
            if (!t.isNullOrBlank()) {
                keysJson = t
                source = "$r/files/wxbridge/keys.json"
                mainHint = RootShell.readText("$r/files/wxbridge/main_db.txt")
                    ?.trim()?.ifBlank { null }
                break
            }
        }

        // ② 回退：模块写进 SharedPreferences 的 XML
        if (keysJson == null) {
            val prefsCandidates = roots.mapNotNull { RootShell.findKeyPrefs(it) }
            val xml = readFirst(prefsCandidates)
                ?: return Result.Fail(
                    "读不到模块写下的密钥。逐个候选根目录检查结果：\n" +
                        roots.joinToString("\n") { r ->
                            "  · $r\n" +
                                "      files/wxbridge: ${RootShell.listWxFiles(r)}\n" +
                                "      shared_prefs:   ${RootShell.listKeyPrefs(r)}"
                        } + "\n" +
                        "→ 确认 LSPosed 里模块已启用、作用域勾了微信\n" +
                        "→ 然后冷启动（强行停止后重开）微信"
                )
            val map = parsePrefs(xml)
            keysJson = map["db_keys"]
                ?: return Result.Fail(
                    "模块还没抓到任何密码（$prefsCandidates）。\n" +
                        "→ 冷启动微信后，先在微信里点开任意一个聊天，再回来读"
                )
            mainHint = map["main_db"]
            source = prefsCandidates.first()
        }

        val keysRaw = keysJson

        // 库里记的路径是微信进程视角的 /data/user/0/...；su 在另一个 namespace，
        // 这里统一映射成当前真正能访问的形态（可能带 /proc/<pid>/root 前缀）
        val keys = parseKeys(keysRaw)
            .mapKeys { RootShell.wxPath(it.key) }
            .toMutableMap()
        if (keys.isEmpty()) {
            return Result.Fail("密钥内容为空：$source")
        }
        onLog("密钥来源：$source（${keys.size} 个库）")

        // 模块会探测出哪个库含 message 表并记下来，优先用这个
        var main: Pair<String, ByteArray>? =
            mainHint?.let { hint ->
                val mapped = RootShell.wxPath(hint)
                keys[mapped]?.let { mapped to it }
            } ?: keys.entries
                .firstOrNull { it.key.endsWith("EnMicroMsg.db") }
                ?.let { it.key to it.value }

        // 上次 App 侧探测出来的主库。库路径必须以当前 MicroMsg/<md5>/ 开头，
        // 换号或重登之后旧缓存会失效，不能blindly信任
        if (main == null && cacheFile != null) {
            val dbDir = keys.keys.firstOrNull()?.substringBeforeLast('/')
            val c = readCache(cacheFile)
            if (c != null && dbDir != null && c.first.substringBeforeLast('/') == dbDir) {
                keys[c.first] = c.second
                main = c.first to c.second
                onLog("用上次的定位结果：${c.first.substringAfterLast('/')}")
            }
        }

        // 模块没定位到（主库懒加载、微信没开过它）时，
        // App 侧自己枚举库目录、用已抓到的 key 逐个试解，看谁有 message 表
        if (main == null && workDir != null) {
            val dbDir = keys.keys.firstOrNull()?.substringBeforeLast('/')
            if (dbDir != null) {
                val hit = io.wxbridge.db.MainDbFinder.find(dbDir, keys, workDir, onLog = onLog)
                if (hit != null) {
                    // 主库的 key 可能是猜出来的公共 key，必须补进 map，
                    // 否则后面按库路径取密码又会取不到
                    keys[hit.path] = hit.key
                    main = hit.path to hit.key
                }
            }
        }

        val mainKey = main ?: return Result.Fail(
            "已抓到 ${keys.size} 个库的密码，但没找到含 message 表的库。\n" +
                "→ 在微信里点开任意一个聊天（主库懒加载）后重试\n" +
                "→ 已抓到：${keys.keys.joinToString { it.substringAfterLast('/') }.take(150)}"
        )

        // 探测出来的结果存一份，下次不用重扫（一次扫描要复制并解密多个库）
        if (cacheFile != null) {
            runCatching {
                cacheFile.writeText(mainKey.first + "\n" + mainKey.second.toHexString())
            }
        }

        return Result.Ok(WxKey(mainKey.first, mainKey.second, keys))
    }

    /** 读 App 侧缓存的主库定位结果（第一行路径、第二行 key 的 hex） */
    private fun readCache(f: java.io.File): Pair<String, ByteArray>? = runCatching {
        if (!f.exists()) return null
        val lines = f.readLines()
        if (lines.size < 2) return null
        val path = lines[0].trim()
        val key = hexToBytes(lines[1].trim()) ?: return null
        if (path.isEmpty()) null else path to key
    }.getOrNull()

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

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
