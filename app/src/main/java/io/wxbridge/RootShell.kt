package io.wxbridge

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 极简 root shell。所有对 /data/data/com.tencent.mm 的访问都走这里。
 *
 * 注意：不要用 `sh -c` 包一层，Magisk / KernelSU 的 su 本身会把 -c 之后的
 * 整个字符串交给 shell 执行，多包一层反而让引号处理变复杂。
 */
object RootShell {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    @Volatile
    private var cachedRoot: Boolean? = null

    fun hasRoot(): Boolean {
        cachedRoot?.let { return it }
        val r = runCatching { exec("id", 5) }.getOrNull()
        val ok = r != null && r.out.contains("uid=0")
        cachedRoot = ok
        return ok
    }

    fun resetRootCache() {
        cachedRoot = null
    }

    fun exec(cmd: String, timeoutSec: Long = 30): Result {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start()
        // 必须并发读流，否则输出超过管道缓冲区会死锁
        val outReader = p.inputStream.bufferedReader()
        val errReader = p.errorStream.bufferedReader()

        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread { runCatching { outReader.readText().let { out.append(it) } } }
        val tErr = Thread { runCatching { errReader.readText().let { err.append(it) } } }
        tOut.start(); tErr.start()

        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            return Result(-1, out.toString(), "timeout after ${timeoutSec}s")
        }
        tOut.join(1000)
        tErr.join(1000)
        return Result(p.exitValue(), out.toString(), err.toString())
    }

    /**
     * 用 root 把 src 复制到 app 私有目录。
     *
     * 先由 app 自己 createNewFile()，让 inode 归属 app uid，
     * 然后 root 的 cp -f 覆盖内容时会保留 owner —— 否则 root 新建的文件
     * app 进程 stat 得到的是 -rw-------，读不了。chmod 是第二重保险。
     */
    fun copyToAppData(src: String, dst: File): Boolean {
        dst.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (!dst.exists()) runCatching { dst.createNewFile() }
        val q = dst.absolutePath
        exec("cp -f '$src' '$q' 2>&1; chmod 666 '$q' 2>&1")
        return dst.exists() && dst.length() > 0
    }

    /** 列目录，返回文件名列表 */
    fun listDir(dir: String): List<String> {
        val r = exec("ls -1 '$dir' 2>/dev/null")
        if (!r.ok && r.out.isBlank()) return emptyList()
        return r.out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** 读一个文本文件 */
    fun readText(path: String, timeoutSec: Long = 10): String? {
        val r = exec("cat '$path' 2>/dev/null", timeoutSec)
        return if (r.out.isBlank()) null else r.out
    }
}
