package io.wxbridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自动回复配置。
 *
 * 这份配置要被**微信进程**读取（hook 侧执行回复），而 App 进程负责编辑。
 * Android 上没有干净的跨进程配置通道，所以走「App 用 root 写微信自己的 files 目录，
 * 微信进程读自己的目录」—— 两端都在微信的 app_data_file 里，不碰 SELinux 边界。
 */
data class AutoReplyConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",

    /** 私聊直接回 */
    val replyPrivate: Boolean = true,
    /** 群聊只在被 @ 时回 */
    val replyGroupAt: Boolean = true,
    /** 只回白名单里的会话 */
    val onlyWhitelist: Boolean = true,
    /** 白名单：talker id 或备注名包含即可 */
    val whitelist: List<String> = emptyList(),
    /** 群里 @ 你时用的名字，检测 @ 依赖它 */
    val myGroupNickname: String = "",

    /** 带多少条上下文喂给模型 */
    val contextCount: Int = 10,

    /** 回复前的随机延迟区间，制造人类节奏 */
    val minDelaySec: Int = 5,
    val maxDelaySec: Int = 12,
    /** 同一会话的冷却时间 */
    val cooldownSec: Int = 180,
    /** 每天最多回多少条，兜底 */
    val dailyLimit: Int = 100,

    /**
     * 允许回复里带 emoji。
     * 微信文本消息原生支持 Unicode emoji，直接发字符就能显示成表情，
     * 不需要模拟点击表情面板 —— 这也是不走无障碍方案的原因。
     * 表情包（贴图/GIF）是另一回事，文本发不了，本版本不做。
     */
    val allowEmoji: Boolean = true,

    /**
     * 对方发表情包时，自动回一个表情包。
     * 表情包是 type=47 的消息，本体在 MicroMsg/<md5>/emoji/ 下按 md5 命名，
     * 发送只需要 md5 + size。
     */
    val autoStickerReply: Boolean = true,

    /** 回表情的概率（0-100）。全量回容易显得像机器人，默认一半 */
    val stickerChance: Int = 50,

    val systemPrompt: String = DEFAULT_PROMPT
) {
    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
        put("replyPrivate", replyPrivate)
        put("replyGroupAt", replyGroupAt)
        put("onlyWhitelist", onlyWhitelist)
        put("whitelist", JSONArray(whitelist))
        put("myGroupNickname", myGroupNickname)
        put("contextCount", contextCount)
        put("minDelaySec", minDelaySec)
        put("maxDelaySec", maxDelaySec)
        put("cooldownSec", cooldownSec)
        put("dailyLimit", dailyLimit)
        put("allowEmoji", allowEmoji)
        put("autoStickerReply", autoStickerReply)
        put("stickerChance", stickerChance)
        put("systemPrompt", systemPrompt)
    }.toString(2)

    companion object {
        const val DEFAULT_PROMPT =
            "你在替微信主人回复消息。用主人口吻，简短自然，一到两句话。" +
                "不要出现「作为AI」「助手」之类的措辞；不确定的事就说稍后回复，绝不编造。" +
                "对方用表情你再考虑用，否则不要加。"

        fun fromJson(raw: String): AutoReplyConfig? = runCatching {
            val o = JSONObject(raw)
            AutoReplyConfig(
                enabled = o.optBoolean("enabled", false),
                baseUrl = o.optString("baseUrl", ""),
                apiKey = o.optString("apiKey", ""),
                model = o.optString("model", ""),
                replyPrivate = o.optBoolean("replyPrivate", true),
                replyGroupAt = o.optBoolean("replyGroupAt", true),
                onlyWhitelist = o.optBoolean("onlyWhitelist", true),
                whitelist = o.optJSONArray("whitelist")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                } ?: emptyList(),
                myGroupNickname = o.optString("myGroupNickname", ""),
                contextCount = o.optInt("contextCount", 10).coerceIn(2, 30),
                minDelaySec = o.optInt("minDelaySec", 5).coerceIn(0, 60),
                maxDelaySec = o.optInt("maxDelaySec", 12).coerceIn(0, 120),
                cooldownSec = o.optInt("cooldownSec", 180).coerceAtLeast(10),
                dailyLimit = o.optInt("dailyLimit", 100).coerceIn(1, 1000),
                allowEmoji = o.optBoolean("allowEmoji", true),
                autoStickerReply = o.optBoolean("autoStickerReply", true),
                stickerChance = o.optInt("stickerChance", 50).coerceIn(0, 100),
                systemPrompt = o.optString("systemPrompt", DEFAULT_PROMPT).ifBlank { DEFAULT_PROMPT }
            )
        }.getOrNull()
    }
}

/**
 * 配置与日志的落点。都在微信自己的私有目录里：
 * App 用 su 写，微信进程读；日志反过来。
 * 不走 /sdcard 是因为 Android 11+ 上第三方 App 读写别人的 media 目录不可靠。
 */
object AutoReplyPaths {
    const val DIR = "/data/data/com.tencent.mm/files/wxbridge"
    const val CFG = "$DIR/cfg.json"
    const val LOG = "$DIR/reply.log"
}

/** App 侧读写（全部走 root） */
object AutoReplyStore {

    fun load(): AutoReplyConfig? {
        val raw = RootShell.readText(AutoReplyPaths.CFG) ?: return null
        return AutoReplyConfig.fromJson(raw)
    }

    /**
     * 写入微信目录。
     *
     * 以前是先写 /data/local/tmp 再 cp 过去 —— 但那个目录是 0711 shell:shell，
     * 普通 App 根本创建不了文件，所以必然失败。现在改成把 JSON 通过 stdin
     * 直接喂给 `cat >`，既不依赖中转文件，也不用操心引号转义。
     *
     * @return null 表示成功，非 null 是失败原因（直接展示给用户）
     */
    fun save(cfg: AutoReplyConfig): String? {
        if (!RootShell.hasRoot()) return "没有 root 权限"

        val json = cfg.toJson()
        val wxUid = "chown \$(stat -c '%u:%g' '/data/data/com.tencent.mm')"

        // 三件事必须一起做，少一件都会让微信进程读不到或写不了：
        //   1. 目录和文件属主改成微信自己的 uid —— 否则微信建不了 reply.log
        //   2. restorecon 修正 SELinux 上下文 —— root 建的文件 MLS 分类可能对不上
        //   3. chmod 兜底，防止某些 ROM 的 umask 把权限压掉
        val cmd = "mkdir -p '${AutoReplyPaths.DIR}' && " +
            "$wxUid '${AutoReplyPaths.DIR}' && chmod 771 '${AutoReplyPaths.DIR}' && " +
            "cat > '${AutoReplyPaths.CFG}' && " +
            "$wxUid '${AutoReplyPaths.CFG}' && chmod 664 '${AutoReplyPaths.CFG}' && " +
            "(restorecon -R '${AutoReplyPaths.DIR}' 2>/dev/null || true) && " +
            "echo __OK__"

        val r = RootShell.execWithInput(cmd, json, 20)

        return when {
            r.out.contains("__OK__") -> null
            r.code == -1 -> "执行超时，su 没有响应"
            r.out.isBlank() && r.err.isBlank() ->
                "su 没有任何输出 —— 检查 Magisk/KernelSU 是否授权了本 App"
            r.err.contains("Permission denied") || r.out.contains("Permission denied") ->
                "权限被拒绝：${(r.err + r.out).trim().take(200)}"
            else -> "写入失败 (exit=${r.code})：${(r.err + r.out).trim().take(300)}"
        }
    }

    fun readLog(tailLines: Int = 200): String {
        val r = RootShell.exec("tail -n $tailLines '${AutoReplyPaths.LOG}' 2>/dev/null", 10)
        return r.out
    }

    fun clearLog(): Boolean =
        RootShell.exec(": > '${AutoReplyPaths.LOG}' && echo OK", 10).out.contains("OK")
}
