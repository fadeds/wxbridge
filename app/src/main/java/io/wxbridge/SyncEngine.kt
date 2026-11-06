package io.wxbridge

import android.content.Context
import io.wxbridge.db.MsgType
import io.wxbridge.db.WxDatabase
import io.wxbridge.db.WxMessage
import io.wxbridge.llm.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 增量同步 + 摘要。
 *
 * 游标用「上一次处理到的最大 createTime」而不是固定时间窗，
 * 这样重跑不会重复花钱，漏跑也不会丢消息。
 */
class SyncEngine(
    private val ctx: Context,
    private val wxDb: WxDatabase
) {
    private val prefs = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    data class Outcome(
        val text: String,
        val messageCount: Int,
        val groupCount: Int,
        val imageStats: String = ""
    )

    suspend fun summarize(
        hours: Int,
        llm: LlmClient,
        images: ImagePipeline? = null,
        talkerFilter: String? = null,
        advanceCursor: Boolean = true
    ): Outcome = withContext(Dispatchers.IO) {
        val repo = wxDb.repo() ?: error("数据库还没打开")

        val since = System.currentTimeMillis() / 1000 - hours * 3600L
        val messages = repo.loadSince(since)
            .filter { it.type != MsgType.SYS }
            .filter { talkerFilter == null || it.talker.contains(talkerFilter) }

        if (messages.isEmpty()) {
            return@withContext Outcome("这个时间段没有可分析的消息", 0, 0)
        }

        val myName = "我"
        val groups = messages.groupBy { it.talker }
        val session = Redactor.newSession()
        // 只扫一次 rcontact，别在循环里反复全表查询
        val names = repo.contactNames()

        // 先把所有行攒成 List，再拼 prompt —— 因为图片分析是 suspend，
        // 而 buildString 的 lambda 不是 suspend 上下文，没法在里面 await。
        val lines = ArrayList<String>(messages.size + groups.size)
        for ((talker, list) in groups) {
            val title = names[talker] ?: talker
            lines += "=== 会话: ${session.alias(talker)}(备注: ${session.redact(title)} · ${list.size} 条)==="
            for (m in list) {
                val body = if (m.type == MsgType.IMAGE && images != null) {
                    runCatching { images.analyze(m) }.getOrNull()?.text ?: m.render()
                } else {
                    m.render()
                }
                val text = session.redact(body)
                if (text.isNotBlank()) {
                    val who = if (m.isSend) myName else session.alias(m.speaker ?: talker)
                    lines += "[${fmt.format(Date(m.createTime * 1000))}] $who: $text"
                }
            }
            lines += ""
        }

        val header = buildString {
            appendLine("以下是最近 ${hours} 小时的微信聊天记录，已脱敏。")
            appendLine("约定：U1/U2 是对方代号；\"$myName\" 是你自己；")
            appendLine("[图片 · ...] 是视觉模型对图片的识别结果，包含图里的文字或画面内容。")
            appendLine()
            appendLine("请输出：")
            appendLine("1. 每个会话一段 3 行以内的摘要，说明聊了什么、有没有待办")
            appendLine("2. 汇总所有待办事项（谁、什么事、有无明确时间）")
            appendLine("3. 需要注意的风险信号（催款、投诉、承诺未兑现等）")
            appendLine("4. 如果图片里有重要信息（金额、地址、单号、截图内容），单独列出")
            appendLine()
        }
        val prompt = header + lines.joinToString("\n")

        val system = "你是一个严谨的中文信息整理助手。只输出结论，不要复述原文，不要编造记录里没有的信息。" +
            "如果某段记录信息量太少就写「无实质内容」。"

        val answer = llm.chat(system, prompt, maxTokens = 2000)

        if (advanceCursor) {
            prefs.edit().putLong(KEY_CURSOR, messages.maxOf { it.createTime }).apply()
        }

        Outcome(
            text = answer,
            messageCount = messages.size,
            groupCount = groups.size,
            imageStats = images?.stats.orEmpty()
        )
    }

    fun cursor(): Long = prefs.getLong(KEY_CURSOR, 0L)

    fun resetCursor() = prefs.edit().remove(KEY_CURSOR).apply()

    private companion object {
        const val KEY_CURSOR = "cursor_create_time"
    }
}

/** 把消息列表格式化成可读文本，调试时用 */
fun List<WxMessage>.preview(limit: Int = 40): String {
    val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
    return takeLast(limit).joinToString("\n") { m ->
        val who = if (m.isSend) "我" else (m.speaker ?: m.talker).take(12)
        "[${fmt.format(Date(m.createTime * 1000))}] $who: ${m.render()}"
    }
}
