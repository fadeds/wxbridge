package io.wxbridge.db

import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** 微信消息类型 → 语义 */
enum class MsgType(val code: Int, val label: String) {
    TEXT(1, "文本"),
    IMAGE(3, "图片"),
    VOICE(34, "语音"),
    CARD(42, "名片"),
    VIDEO(43, "视频"),
    EMOJI(47, "表情"),
    LOCATION(48, "位置"),
    APP(49, "链接/文件"),
    SYS(10000, "系统"),
    RECALL(10002, "撤回"),
    UNKNOWN(-1, "其他");

    companion object {
        fun of(code: Int): MsgType = values().firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class WxMessage(
    val msgId: Long,
    val msgSvrId: Long,
    val typeCode: Int,
    val isSend: Boolean,
    val createTime: Long,
    val talker: String,
    val content: String,
    val imgPath: String?
) {
    val type: MsgType get() = MsgType.of(typeCode)

    /** 群聊里 content 会带 "wxid_xxx:\n" 前缀 */
    val speaker: String? get() =
        if (talker.endsWith("@chatroom") && content.contains(":\n"))
            content.substringBefore(":\n").takeIf { it.startsWith("wxid_") || it.contains("@") }
        else null

    val body: String get() = speaker?.let { content.substringAfter(":\n") } ?: content

    /** 交给 LLM 的纯文本形态，二进制一律降级成占位符 */
    fun render(): String = when (type) {
        MsgType.TEXT -> body
        MsgType.IMAGE -> "[图片]"
        MsgType.VOICE -> "[语音]"
        MsgType.VIDEO -> "[视频]"
        MsgType.EMOJI -> "[表情]"
        MsgType.LOCATION -> "[位置]"
        MsgType.APP -> appTitle(body) ?: "[链接/文件]"
        MsgType.CARD -> "[名片]"
        MsgType.SYS -> ""
        MsgType.RECALL -> "[撤回了一条消息]"
        MsgType.UNKNOWN -> "[${type.label}]"
    }

    /** type=49 的 content 是一段 XML，title 在里面 */
    private fun appTitle(xml: String): String? {
        if (!xml.contains("<appmsg")) return null
        val t = Regex("<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.getOrNull(1)?.trim()
        if (t.isNullOrBlank()) return null
        return "[${if (xml.contains("<type>6</type>")) "文件" else "链接"}] $t"
    }
}

/**
 * message / rcontact 的只读查询。
 * 列名用 PRAGMA 动态探测，避免某个版本少了字段就整个崩掉。
 */
class MainRepo(private val db: SQLiteDatabase) {

    fun tableNames(): List<String> =
        query("SELECT name FROM sqlite_master WHERE type='table'") { it.getString(0) }

    fun columnsOf(table: String): Set<String> =
        runCatching {
            query("PRAGMA table_info($table)") { it.getString(1) }.toSet()
        }.getOrDefault(emptySet())

    fun hasMessageTable() = columnsOf("message").isNotEmpty()

    /** 增量拉取，sinceTime 为秒级时间戳 */
    fun loadSince(sinceTime: Long, limit: Int = 800): List<WxMessage> {
        val cols = columnsOf("message")
        if (cols.isEmpty()) return emptyList()

        fun c(name: String) = if (name in cols) name else "NULL"
        val sql = """
            SELECT msgId, ${c("msgSvrId")}, ${c("type")}, ${c("isSend")},
                   ${c("createTime")}, ${c("talker")}, ${c("content")}, ${c("imgPath")}
            FROM message
            WHERE ${c("createTime")} > ?
            ORDER BY ${c("createTime")} ASC, ${c("msgId")} ASC
            LIMIT ?
        """.trimIndent()

        return queryList(sql, arrayOf<Any>(sinceTime, limit)) { cur ->
            WxMessage(
                msgId = cur.getLong(0),
                msgSvrId = if (cur.isNull(1)) 0L else cur.getLong(1),
                typeCode = cur.getInt(2),
                isSend = cur.getInt(3) == 1,
                createTime = cur.getLong(4),
                talker = cur.getString(5) ?: "",
                content = cur.getString(6) ?: "",
                imgPath = cur.getString(7)
            )
        }
    }

    fun maxCreateTime(): Long =
        query("SELECT max(createTime) FROM message") { if (it.isNull(0)) 0L else it.getLong(0) }
            .firstOrNull() ?: 0L

    /** rcontact 里的显示名：备注 > 昵称 > 微信号 */
    fun contactNames(): Map<String, String> {
        val cols = columnsOf("rcontact")
        if (cols.isEmpty()) return emptyMap()
        val sql = """
            SELECT username,
                   ${if ("conRemark" in cols) "conRemark" else "''"},
                   ${if ("nickname" in cols) "nickname" else "''"},
                   ${if ("alias" in cols) "alias" else "''"}
            FROM rcontact
        """.trimIndent()
        return queryList(sql, null) { cur ->
            val user = cur.getString(0) ?: return@queryList null
            val remark = cur.getString(1).orEmpty()
            val nick = cur.getString(2).orEmpty()
            val alias = cur.getString(3).orEmpty()
            val display = remark.ifBlank { nick }.ifBlank { alias }.ifBlank { user }
            user to display
        }.filterNotNull().toMap()
    }

    // ---- 低层封装 ----

    private fun <T> query(sql: String, map: (Cursor) -> T): List<T> =
        queryList(sql, null, map)

    private fun <T> queryList(sql: String, args: Array<Any>?, map: (Cursor) -> T): List<T> {
        var cur: Cursor? = null
        val out = ArrayList<T>()
        try {
            cur = db.rawQuery(sql, args)
            while (cur.moveToNext()) out.add(map(cur))
        } catch (t: Throwable) {
            // 表不存在 / 列缺失，当作空结果，不要让上层炸掉
        } finally {
            runCatching { cur?.close() }
        }
        return out
    }
}
