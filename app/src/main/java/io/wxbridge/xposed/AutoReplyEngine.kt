package io.wxbridge.xposed

import android.content.ContentValues
import android.database.Cursor
import io.wxbridge.AutoReplyConfig
import io.wxbridge.AutoReplyPaths
import io.wxbridge.llm.LlmClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 自动回复。跑在**微信进程**里，原因有三：
 *   1. 主库实例已经在内存里且是解密状态，查询不用密码也不用复制
 *   2. 发送 API 只能在微信进程调用
 *   3. 能直接挂到存储层，消息一落库就触发，不用轮询
 *
 * 触发方式是**事件驱动**：NewMessageHook 监听 message 表的 insert，
 * 只有真有消息时才动。没消息时这个模块完全静默，不占电。
 * 万一 insert hook 没挂上，才降级成 60 秒一次的低频轮询。
 *
 * 两种回复形态：
 *   - 文本（type=1）：取上下文喂模型，走 sendText。emoji 直接在文本里带字符。
 *   - 表情包（type=47）：对方发了个表情，按概率回一个本地表情包，走 sendEmoji。
 *     群里不自动回表情 —— 太容易被当成刷屏。
 *
 * 安全设计（缺一不可，否则必封）：
 *   - 全局开关默认关
 *   - 白名单模式默认开
 *   - 回复前随机延迟，模拟打字
 *   - 同一会话冷却 180 秒
 *   - 每日总量上限 100 条
 *   - 群聊只在被 @ 时回文本，且不回表情
 */
internal object AutoReplyEngine {

    private const val TAG = "AutoReply"
    private const val FALLBACK_POLL_SEC = 60L

    private var started = false
    private var hookActive = false
    private var lastMsgId = 0L
    private var lastCfgMtime = 0L
    private var cfg: AutoReplyConfig? = null

    private var executor: ScheduledExecutorService? = null

    private val lastReplyAt = HashMap<String, Long>()
    private var dailyCount = 0
    private var dailyDay = -1

    /** 去重：同一条消息可能被 insert 多次（微信常先插入再更新） */
    private val seen = LinkedHashMap<String, Unit>()

    private data class Row(
        val msgId: Long,
        val type: Int,
        val isSend: Boolean,
        val talker: String,
        val content: String,
        val createTime: Long = 0L
    )

    fun start(cl: ClassLoader) {
        if (started) return
        started = true

        WxSender.init(cl)
        xlog("AutoReply: ${WxSender.describe()}")

        val ex = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "wxbridge-autoreply").apply { isDaemon = true }
        }
        executor = ex

        hookActive = NewMessageHook.install(cl) { cv ->
            // hook 回调跑在微信的 DB 线程上，必须立刻返回，不能在这里做任何耗时操作
            ex.submit { runCatching { onInsert(cv) }.onFailure { xlog("onInsert error: $it") } }
        }

        if (hookActive) {
            log("事件驱动就绪：消息落库即触发，不轮询")
        } else {
            log("insert hook 未挂上，降级为 ${FALLBACK_POLL_SEC}s 低频轮询")
            ex.scheduleWithFixedDelay(
                { runCatching { pollTick() }.onFailure { xlog("poll error: $it") } },
                30, FALLBACK_POLL_SEC, TimeUnit.SECONDS
            )
        }
    }

    // ---------------- 事件入口 ----------------

    private fun onInsert(cv: ContentValues) {
        val row = rowOf(cv) ?: return
        if (row.isSend) return
        if (!markSeen(row)) return

        val c = loadCfg() ?: return
        if (!c.enabled) return

        consider(row, c)
    }

    private fun rowOf(cv: ContentValues): Row? {
        val talker = cv.str("talker")?.takeIf { it.isNotBlank() } ?: return null
        return Row(
            msgId = cv.long("msgId") ?: 0L,
            type = cv.int("type") ?: 0,
            isSend = (cv.int("isSend") ?: 0) == 1,
            talker = talker,
            content = cv.str("content") ?: "",
            createTime = cv.long("createTime") ?: 0L
        )
    }

    /**
     * 去重键：同一条消息微信常 insert 多次（先插入再更新）。
     * 用 createTime 而不是 msgId —— msgId 是自增列，往往不在 ContentValues 里。
     */
    private fun markSeen(r: Row): Boolean {
        val key = "${r.talker}|${r.type}|${r.createTime}|${r.content.hashCode()}"
        if (seen.containsKey(key)) return false
        seen[key] = Unit
        if (seen.size > 400) {
            val it = seen.keys.iterator()
            repeat(120) {
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }
        return true
    }

    // ---------------- 规则 ----------------

    private fun consider(m: Row, c: AutoReplyConfig) {
        if (!WxSender.textAvailable() && !WxSender.emojiAvailable()) return
        if (m.content.isBlank() && m.type != 47) return

        val isGroup = m.talker.endsWith("@chatroom")

        if (isGroup) {
            if (!c.replyGroupAt) return
            // 群里只处理「被 @ 的文本」；表情消息不参与，否则容易刷屏
            if (m.type != 1) return
            val nick = c.myGroupNickname
            if (nick.isBlank()) {
                log("群消息命中但没配「我的群昵称」，跳过：${m.talker}")
                return
            }
            // 群消息 content 形如 "wxid_xxx:\n@昵称\u2005内容"
            if (!m.content.contains("@$nick")) return
        } else {
            if (!c.replyPrivate) return
        }

        if (c.onlyWhitelist && c.whitelist.none { m.talker.contains(it) }) return

        val now = System.currentTimeMillis()
        if (now - (lastReplyAt[m.talker] ?: 0L) < c.cooldownSec * 1000L) return

        val day = (now / 86_400_000L).toInt()
        if (dailyDay != day) {
            dailyDay = day
            dailyCount = 0
        }
        if (dailyCount >= c.dailyLimit) {
            log("已达日限额 ${c.dailyLimit}，暂停到明天")
            return
        }

        when (m.type) {
            1 -> {
                lastReplyAt[m.talker] = now
                dailyCount++
                replyText(m, c)
            }
            47 -> {
                if (!c.autoStickerReply) return
                if (Random.nextInt(100) >= c.stickerChance) return
                lastReplyAt[m.talker] = now
                dailyCount++
                replySticker(m, c)
            }
            else -> Unit
        }
    }

    // ---------------- 文本回复 ----------------

    private fun replyText(m: Row, c: AutoReplyConfig) {
        val db = WcdbKeyHook.mainDb
        val context = if (db != null) queryContext(db, m.talker, c.contextCount) else listOf(m)
        val dialogue = context.joinToString("\n") {
            "${if (it.isSend) "我" else "对方"}: ${render(it)}"
        }

        // emoji 就是普通 Unicode 字符，文本消息直接带就能显示，不需要任何 UI 操作
        val system = if (c.allowEmoji) {
            "${c.systemPrompt} 可以自然地用 emoji，但别滥用，一句话最多一个。"
        } else {
            "${c.systemPrompt} 不要使用任何 emoji 或颜文字。"
        }

        val llm = LlmClient(c.baseUrl, c.apiKey, c.model)
        val answer = runCatching {
            runBlocking {
                llm.chat(
                    system = system,
                    user = "以下是最近对话：\n$dialogue\n\n请只输出你要回复的那一句话。",
                    maxTokens = 200
                )
            }
        }.getOrElse {
            log("生成失败: ${it.message?.take(80)}")
            return
        }.trim().replace(Regex("\\s+"), " ")

        if (answer.isBlank()) return
        if (answer.length > 200) return  // 异常长，多半是模型跑偏了，丢掉

        val delay = delayOf(c)
        runCatching { Thread.sleep(delay * 1000L) }

        val ok = WxSender.sendText(m.talker, answer)
        log("${if (ok) "已回复" else "发送失败"} [${delay}s] → ${m.talker}: ${answer.take(60)}")
    }

    // ---------------- 表情包回复 ----------------

    private fun replySticker(m: Row, c: AutoReplyConfig) {
        if (!WxSender.emojiAvailable()) {
            log("表情发送入口不可用，跳过（看启动日志里的「表情=」）")
            return
        }
        val pick = EmojiRepo.pickRandom(WcdbKeyHook.mainDbPath)
        if (pick == null) {
            log("本地表情包为空，跳过。检查 MicroMsg/<md5>/emoji/ 是否存在")
            return
        }

        val delay = delayOf(c)
        runCatching { Thread.sleep(delay * 1000L) }

        val ok = WxSender.sendEmoji(m.talker, pick.md5, pick.size)
        log(
            "${if (ok) "已回表情" else "表情发送失败"} [${delay}s] → ${m.talker}: " +
                "${pick.md5.take(8)}… (${pick.size / 1024}KB)"
        )
    }

    private fun delayOf(c: AutoReplyConfig): Int =
        if (c.maxDelaySec > c.minDelaySec) Random.nextInt(c.minDelaySec, c.maxDelaySec + 1)
        else c.minDelaySec

    // ---------------- 兜底轮询（仅在 hook 失败时启用） ----------------

    private fun pollTick() {
        val c = loadCfg() ?: return
        if (!c.enabled) return
        val db = WcdbKeyHook.mainDb ?: return

        if (lastMsgId == 0L) {
            lastMsgId = queryMaxId(db)
            return
        }

        val rows = queryNew(db, lastMsgId)
        if (rows.isEmpty()) return
        for (r in rows) {
            lastMsgId = maxOf(lastMsgId, r.msgId)
            if (r.isSend) continue
            runCatching { consider(r, c) }.onFailure { log("处理失败: $it") }
        }
    }

    // ---------------- 库查询（反射，微信的 SQLiteDatabase 编译期不存在） ----------------

    private fun rawQuery(db: Any, sql: String, args: Array<String>): Cursor? {
        val m = db.javaClass.methods.firstOrNull {
            it.name == "rawQuery" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java && it.parameterTypes[1].isArray
        } ?: return null
        return runCatching { m.invoke(db, sql, args) as? Cursor }.getOrNull()
    }

    private fun queryMaxId(db: Any): Long {
        val cur = rawQuery(db, "SELECT max(msgId) FROM message", arrayOf()) ?: return 0L
        return try {
            if (cur.moveToFirst() && !cur.isNull(0)) cur.getLong(0) else 0L
        } finally {
            runCatching { cur.close() }
        }
    }

    private fun queryNew(db: Any, sinceId: Long, limit: Int = 20): List<Row> {
        val cur = rawQuery(
            db,
            "SELECT msgId, type, isSend, talker, content, createTime FROM message " +
                "WHERE msgId > ? ORDER BY msgId ASC LIMIT ?",
            arrayOf(sinceId.toString(), limit.toString())
        ) ?: return emptyList()
        return readRows(cur)
    }

    private fun queryContext(db: Any, talker: String, n: Int): List<Row> {
        val cur = rawQuery(
            db,
            "SELECT msgId, type, isSend, talker, content, createTime FROM message " +
                "WHERE talker = ? ORDER BY msgId DESC LIMIT ?",
            arrayOf(talker, n.toString())
        ) ?: return emptyList()
        // 取的是倒序，喂给模型前翻回来
        return readRows(cur).reversed()
    }

    private fun readRows(cur: Cursor): List<Row> {
        val out = ArrayList<Row>()
        try {
            while (cur.moveToNext()) {
                out += Row(
                    msgId = cur.getLong(0),
                    type = cur.getInt(1),
                    isSend = cur.getInt(2) == 1,
                    talker = cur.getString(3) ?: "",
                    content = cur.getString(4) ?: "",
                    createTime = cur.getLong(5)
                )
            }
        } finally {
            runCatching { cur.close() }
        }
        return out
    }

    private fun render(r: Row): String = when (r.type) {
        1 -> r.content.substringAfter(":\n", r.content)
        3 -> "[图片]"
        34 -> "[语音]"
        43 -> "[视频]"
        47 -> "[表情]"
        49 -> "[链接/文件]"
        else -> "[消息]"
    }

    // ---------------- ContentValues 安全读取（类型不符会抛，一律吞掉） ----------------

    private fun ContentValues.str(k: String): String? = runCatching { getAsString(k) }.getOrNull()
    private fun ContentValues.int(k: String): Int? = runCatching { getAsInteger(k) }.getOrNull()
    private fun ContentValues.long(k: String): Long? = runCatching { getAsLong(k) }.getOrNull()

    // ---------------- 配置与日志 ----------------

    private fun loadCfg(): AutoReplyConfig? {
        val f = File(AutoReplyPaths.CFG)
        if (!f.exists()) return null
        val mtime = f.lastModified()
        if (mtime != lastCfgMtime) {
            lastCfgMtime = mtime
            cfg = runCatching { AutoReplyConfig.fromJson(f.readText()) }.getOrNull()
            log("配置已加载：enabled=${cfg?.enabled} 白名单=${cfg?.whitelist?.size ?: 0}")
        }
        return cfg
    }

    private fun log(msg: String) {
        xlog("[$TAG] $msg")
        runCatching {
            val f = File(AutoReplyPaths.LOG)
            f.parentFile?.mkdirs()
            if (!f.exists()) f.createNewFile()
            val line = "${SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())} $msg\n"
            // 超过 512KB 就重开，别让日志把微信的 data 撑爆
            if (f.length() > 512 * 1024) f.writeText(line) else f.appendText(line)
        }
    }
}
