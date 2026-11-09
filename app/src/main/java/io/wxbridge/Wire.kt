package io.wxbridge

import java.io.InputStream
import java.io.OutputStream

/**
 * 本地套接字上的行协议。
 *
 * 刻意不用 BufferedReader：它会在读到换行时顺手把后面的负载也吃进缓冲区，
 * 之后再按「先读一行头、再读 N 字节」的方式取正文就会少字节。
 * 这里逐字节读到 '\n' 为止，代价是每字节一次系统调用 —— 但请求头只有几十字节，
 * 正文用 bulk 读写，实际开销可以忽略。
 */
object Wire {

    private const val MAX_LINE = 8 * 1024 * 1024

    /** 读一行（不含换行符）。对端关闭返回 null */
    fun readLine(ins: InputStream): String? {
        val sb = StringBuilder(128)
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b == '\r'.code) continue
            if (sb.length >= MAX_LINE) throw IllegalStateException("行过长")
            sb.append(b.toChar())
        }
    }

    fun writeLine(out: OutputStream, s: String) {
        out.write(s.toByteArray(Charsets.UTF_8))
        out.write('\n'.code)
        out.flush()
    }

    /**
     * 回包帧：`OK <字节数>\n` 或 `ERR <字节数>\n`，紧跟正文原始字节。
     *
     * 走广播时被迫 base64 + 120KB 分片（Binder 的 1MB 事务上限 + String 额外开销），
     * 本地套接字没有这个限制，正文直接 UTF-8 过去，一次写完。
     */
    fun writeFrame(out: OutputStream, ok: Boolean, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        writeLine(out, "${if (ok) "OK" else "ERR"} ${bytes.size}")
        out.write(bytes)
        out.flush()
    }

    /** 读回包：返回 (是否成功, 正文)。对端关闭返回 null */
    fun readFrame(ins: InputStream): Pair<Boolean, String>? {
        val header = readLine(ins) ?: return null
        val ok = when {
            header.startsWith("OK ") -> true
            header.startsWith("ERR ") -> false
            else -> throw IllegalStateException("非法帧头: ${header.take(40)}")
        }
        val len = header.substringAfter(' ').trim().toIntOrNull()
            ?: throw IllegalStateException("帧头没有长度: ${header.take(40)}")
        if (len < 0 || len > MAX_LINE) throw IllegalStateException("长度越界: $len")
        return ok to String(readFully(ins, len), Charsets.UTF_8)
    }

    private fun readFully(ins: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = ins.read(buf, off, len - off)
            if (n < 0) throw IllegalStateException("正文不完整：期望 $len，收到 $off")
            off += n
        }
        return buf
    }
}
