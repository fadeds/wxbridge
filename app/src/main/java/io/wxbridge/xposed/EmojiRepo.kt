package io.wxbridge.xposed

import java.io.File

/**
 * 读本地表情包。
 *
 * 微信把自定义表情存在 `MicroMsg/<md5>/emoji/` 下，文件名就是表情的 md5
 * （32 位十六进制），扩展名 gif/png。发送时只需要 md5 + 文件大小，
 * 所以这里不用碰 emoticon.db，扫目录就够 —— 少一次解密，也少一个版本依赖。
 *
 * 注意 `_thumb` 结尾的是缩略图，不能当表情发；过大的文件也排除（可能是损坏或异常文件）。
 */
internal object EmojiRepo {

    data class Emoji(val md5: String, val size: Int)

    private var cache: List<Emoji> = emptyList()
    private var cachedAt = 0L

    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_SIZE = 2 * 1024 * 1024

    fun list(dbDir: String?): List<Emoji> {
        if (dbDir.isNullOrBlank()) return emptyList()

        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cachedAt < TTL_MS) return cache

        val dir = File(dbDir, "emoji")
        if (!dir.isDirectory) return emptyList()

        val out = dir.listFiles()?.asSequence()
            // File.length() 是 Long，范围必须用 Long，否则类型不匹配
            ?.filter { it.isFile && it.length() in 1L..MAX_SIZE.toLong() }
            ?.mapNotNull { toEmoji(it) }
            ?.toList()
            ?: emptyList()

        cache = out
        cachedAt = now
        xlog("EmojiRepo: 本地表情包 ${out.size} 个")
        return out
    }

    fun pickRandom(dbDir: String?): Emoji? = list(dbDir).randomOrNull()

    private fun toEmoji(f: File): Emoji? {
        val n = f.name
        if (n.contains("thumb", ignoreCase = true)) return null
        val stem = n.substringBefore('.')
        // md5 是 32 位十六进制
        if (stem.length != 32 || !stem.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        return Emoji(stem.lowercase(), f.length().toInt())
    }
}
