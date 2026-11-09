package io.wxbridge.xposed

import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 草稿箱（微信进程侧写入）。
 *
 * 草稿模式下模型照常生成，但**不发出去**，先落成一行 JSON 追加到
 * `files/wxbridge/drafts.jsonl`，由 App 读出来逐条确认。
 *
 * 为什么用 JSONL 而不是 JSON 数组：写入是「追加一行」的原子操作，
 * 不需要读出整个文件改完再写回 —— 想删草稿时 App 那边才做重写。
 */
internal object DraftStore {

    /** 超过这个体积就只保留最近 [KEEP_LINES] 条，别让草稿把微信的 data 撑爆 */
    private const val MAX_BYTES = 256 * 1024
    private const val KEEP_LINES = 100

    data class Draft(
        val id: String,
        val ts: Long,
        val talker: String,
        val text: String
    ) {
        fun toJson(): String = JSONObject().apply {
            put("id", id)
            put("ts", ts)
            put("talker", talker)
            put("text", text)
        }.toString()

        companion object {
            fun fromJson(o: JSONObject): Draft? {
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
                val text = o.optString("text").takeIf { it.isNotBlank() } ?: return null
                return Draft(id, o.optLong("ts", 0L), o.optString("talker"), text)
            }
        }
    }

    private fun dirOf(): File? {
        val app = runCatching { android.app.AndroidAppHelper.currentApplication() }.getOrNull()
            ?: return null
        return File(app.filesDir, "wxbridge")
    }

    fun append(talker: String, text: String): Boolean {
        val dir = dirOf() ?: return false
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) return@runCatching false
            val f = File(dir, "drafts.jsonl")

            val d = Draft(
                id = UUID.randomUUID().toString().replace("-", "").take(12),
                ts = System.currentTimeMillis(),
                talker = talker,
                text = text
            )
            f.appendText(d.toJson() + "\n")

            if (f.length() > MAX_BYTES) trim(f)
            true
        }.getOrDefault(false)
    }

    /** 只保留最近若干条。解析失败的行直接丢掉 —— 草稿不是需要长期保存的数据 */
    private fun trim(f: File) {
        runCatching {
            val lines = f.readLines().filter { it.isNotBlank() }
            val keep = lines.takeLast(KEEP_LINES)
            f.writeText(keep.joinToString("\n", postfix = if (keep.isEmpty()) "" else "\n"))
        }
    }
}
