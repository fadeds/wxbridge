package io.wxbridge

import org.json.JSONObject

/**
 * 草稿箱（App 侧读取与删除）。
 *
 * 文件由微信进程追加（一行一条 JSON），App 只做「读出来」和「删掉某条」。
 * 删除要重写整个文件，所以走 root：先用 su 读出来，改完再 `cat >` 写回去，
 * 并且必须 chown 回微信的 uid —— 否则微信进程下次追加会 Permission denied。
 */
object Drafts {

    data class Draft(
        val id: String,
        val ts: Long,
        val talker: String,
        val text: String
    )

    private fun path(): String = AutoReplyPaths.draftApp()

    /** 读出全部草稿，最新的在前。阻塞，IO 线程调用 */
    fun list(): List<Draft> {
        val raw = RootShell.readText(path()) ?: return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching {
                    val o = JSONObject(line)
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@runCatching null
                    val text = o.optString("text").takeIf { it.isNotBlank() } ?: return@runCatching null
                    Draft(id, o.optLong("ts", 0L), o.optString("talker"), text)
                }.getOrNull()
            }
            .toList()
            .sortedByDescending { it.ts }
    }

    fun remove(id: String): Boolean {
        val raw = RootShell.readText(path()) ?: return false
        val kept = raw.lineSequence()
            .filter { it.isNotBlank() }
            .filter { line ->
                // 解析不了的行保留，别因为一行坏数据把别的草稿一起清了
                runCatching { JSONObject(line).optString("id") != id }.getOrDefault(true)
            }
            .toList()
        return write(kept)
    }

    fun clear(): Boolean = write(emptyList())

    private fun write(lines: List<String>): Boolean {
        val p = path()
        val wxRoot = RootShell.wxRoot()
        val owner = "chown \$(stat -c '%u:%g' '$wxRoot')"
        val body = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
        // 前半段是「文件已存在就先改属主」—— 不存在时 cat > 会以 root 新建，
        // 所以后半段再 chown 一次。整段用 || true 包住，别让 test 失败把链路掐断。
        val cmd = "(test -f '$p' && $owner '$p' && chmod 664 '$p' || true) && " +
            "cat > '$p' && $owner '$p' && chmod 664 '$p' && echo __OK__"
        val r = RootShell.execWithInput(cmd, body, 20)
        return r.out.contains("__OK__")
    }
}
