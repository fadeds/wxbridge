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
        // /proc/self/attr/current 之类输出末尾带 \u0000，留在字符串里会把
        // TextView 变成非法 XML 字符（uiautomator/无障碍全都读不了界面），这里统一清掉
        return Result(p.exitValue(), clean(out.toString()), clean(err.toString()))
    }

    /** 去掉控制字符（保留换行和制表），NUL 转成换行 */
    private fun clean(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c == '\u0000' -> sb.append('\n')
                c == '\n' || c == '\t' -> sb.append(c)
                c.code < 0x20 -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 用 root 把 src 复制到 app 私有目录。
     *
     * 先由 app 自己 createNewFile()，让 inode 归属 app uid，
     * 然后 root 的 cp -f 覆盖内容时会保留 owner —— 否则 root 新建的文件
     * app 进程 stat 得到的是 -rw-------，读不了。chmod 是第二重保险。
     */
    fun copyToAppData(src: String, dst: File, timeoutSec: Long = 30): Boolean {
        dst.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (!dst.exists()) runCatching { dst.createNewFile() }
        val q = dst.absolutePath
        exec("cp -f '$src' '$q' 2>&1; chmod 666 '$q' 2>&1", timeoutSec)
        return dst.exists() && dst.length() > 0
    }

    /**
     * 带 stdin 的执行。
     *
     * 写配置文件必须用这种方式：App 进程没有权限创建 /data/local/tmp 下的文件
     * （那个目录是 0711 shell:shell），而把 JSON 拼进 shell 命令又要处理一堆引号转义。
     * 用 `cat > file` 接 stdin 是最干净的做法。
     */
    fun execWithInput(cmd: String, input: String, timeoutSec: Long = 20): Result {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start()
        val out = StringBuilder()
        val err = StringBuilder()

        // 先起读线程，避免子进程输出把管道撑满导致死锁
        val tOut = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }
        val tErr = Thread { runCatching { err.append(p.errorStream.bufferedReader().readText()) } }
        tOut.start()
        tErr.start()

        runCatching {
            p.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
        }

        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            return Result(-1, out.toString(), "timeout after ${timeoutSec}s")
        }
        tOut.join(1500)
        tErr.join(1500)
        return Result(p.exitValue(), out.toString(), err.toString())
    }

    /**
     * 微信数据目录的实际路径候选。
     *
     * 两个坑叠在一起：
     *
     * 1) 微信用的是「手机分身」（XSpace），进程属于 user 999：
     *        u999_a15  14544  ... com.tencent.mm
     *    它的数据在 /data/user/999/com.tencent.mm，user 0 下没有。
     *
     * 2) 但 su 在 init 的 mount namespace，`ls /data/user` 只能看到 `0` ——
     *    每个用户的 CE 存储只在各自 user 的 namespace 里挂载。
     *    所以要借微信进程的 namespace：/proc/<pid>/root/data/user/<uid>/...
     *
     * 用户号从进程 uid 反推：uid / 100000 = userId（u999_a15 → 99901015 → 999）。
     */
    fun wxCandidates(): List<String> = wxCandidatesOf().candidates

    /**
     * 一次算完：候选列表 + 每个微信进程的 (pid, userId)。
     * 每次 stat 都是一次 su 调用（约 100ms），不能重复算。
     */
    private data class WxProbe(
        val candidates: List<String>,
        val procs: List<Pair<Int, Int>>
    )

    @Volatile
    private var probeCache: WxProbe? = null

    private fun wxCandidatesOf(force: Boolean = false): WxProbe {
        if (!force) probeCache?.let { return it }

        val out = LinkedHashSet<String>()
        val procs = mutableListOf<Pair<Int, Int>>()

        val pids = exec("pidof com.tencent.mm 2>/dev/null", 8).out
            .trim().split(' ').mapNotNull { it.trim().toIntOrNull() }

        for (pid in pids) {
            val uid = exec("stat -c '%u' /proc/$pid 2>/dev/null", 8).out.trim().toLongOrNull()
                ?: continue
            if (uid <= 0) continue
            val userId = (uid / 100000).toInt()
            procs += pid to userId
            // 借这个进程的 namespace —— 分身的数据只在它自己的 namespace 里
            out += "/proc/$pid/root/data/user/$userId/${WxPaths.WX_PKG}"
        }
        for ((_, u) in procs) out += "/data/user/$u/${WxPaths.WX_PKG}"
        exec("ls -1 /data/user 2>/dev/null", 8).out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.all { c -> c.isDigit() } }
            .forEach { u -> out += "/data/user/$u/${WxPaths.WX_PKG}" }
        out += "/data/data/${WxPaths.WX_PKG}"

        val r = WxProbe(out.toList(), procs)
        probeCache = r
        return r
    }

    /** 只有真的含微信数据的目录才算数 —— 空目录可能是以前误建的 */
    private fun isWxDataRoot(p: String): Boolean =
        exists("$p/MicroMsg") || exists("$p/shared_prefs")

    @Volatile
    private var wxRootCached: String? = null

    fun wxRoot(refresh: Boolean = false): String {
        if (!refresh) wxRootCached?.let { return it }
        val cands = wxCandidatesOf(refresh).candidates
        // 必须含 MicroMsg 或 shared_prefs 才算命中，否则会被空目录带偏
        val hit = cands.firstOrNull { isWxDataRoot(it) }
            ?: cands.firstOrNull { exists("$it/MicroMsg") }
            ?: WxPaths.WX_DATA_USER
        wxRootCached = hit
        return hit
    }

    /** 把任意写法的微信路径映射成当前 namespace 下真正能访问的路径 */
    fun wxPath(path: String): String {
        val marker = "/com.tencent.mm"
        val i = path.indexOf(marker)
        if (i < 0) return path
        return wxRoot() + path.substring(i + marker.length)
    }

    /** 路径是否存在（走 root，用于判断该用 /data/user/0 还是 /data/data 形态） */
    fun exists(path: String): Boolean {
        val r = exec("test -e '$path' && echo 1 || echo 0", 8)
        return r.out.trim().endsWith("1")
    }

    /**
     * 在某个微信数据根下找模块写的密码文件。
     *
     * 不写死文件名：项目从 wxai 改名到 wxbridge 时 prefs 名字跟着变过，
     * 老版本留下的是 wxai_bridge.xml。扫目录比猜名字可靠。
     *
     * 注意这里 **不能** 把 stderr 丢掉 —— 早先 `2>/dev/null` 把
     * "Permission denied" 吞了，看起来像「没有文件」，白排查了好几轮。
     */
    fun findKeyPrefs(root: String): String? {
        val r = exec("ls -1 '$root/shared_prefs' 2>&1", 12)
        val line = r.out.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("wxbridge", true) || it.contains("wxai", true) }
            ?: return null
        if (line.endsWith(":") || line.contains(" ")) return null   // 报错行，不是文件名
        return "$root/shared_prefs/$line"
    }

    /** 列 shared_prefs 的原始输出（含报错），用于诊断 */
    fun listKeyPrefs(root: String): String {
        val r = exec("ls -1 '$root/shared_prefs' 2>&1 | head -10", 12)
        return r.out.trim().replace("\n", " | ")
    }

    /** 列 files/wxbridge 的原始输出（含报错），用于诊断 */
    fun listWxFiles(root: String): String {
        val r = exec("ls -1 '$root/files/wxbridge' 2>&1 | head -10", 12)
        return r.out.trim().replace("\n", " | ")
    }

    /**
     * root 全链路诊断。
     *
     * 背景：遇到过「id 返回 uid=0，但 ls 微信目录是空的」——
     * 因为 listDir 把 stderr 丢进了 /dev/null，权限被拒时看起来只是「没有文件」。
     * 这段不做任何过滤，把 uid / SELinux 上下文 / 真实报错全打出来。
     */
    fun diagnose(): String {
        val probe = wxCandidatesOf(force = true)
        val procLine = probe.procs.joinToString("  ") { (pid, u) -> "pid=$pid user=$u" }
            .ifBlank { "（没有在跑的微信进程）" }
        val candList = probe.candidates.joinToString("\n  ")
        val root = wxRoot(refresh = true)
        val prefs = "$root/shared_prefs/wxbridge.xml"
        val prefsInfo = probe.candidates.joinToString("\n") { r ->
            "  $r\n      files/wxbridge: ${listWxFiles(r)}\n      shared_prefs:   ${listKeyPrefs(r)}"
        }
        val cmd = """
            echo '= uid ='; id 2>&1
            echo '= 当前 SELinux 上下文 ='; cat /proc/self/attr/current 2>&1
            echo '= getenforce ='; getenforce 2>&1
            echo '= su 位置 ='; command -v su 2>&1
            echo '= 微信进程 ='; ps -A 2>/dev/null | grep -i 'com.tencent.mm' | head -3
            echo '= su 可见用户 ='; ls -1 /data/user 2>&1 | head -6
            echo '= 解析出的进程与用户 ='; echo '$procLine'
            echo '= 候选目录 ='; echo '  $candList'
            echo '= 选中的根路径 ='; echo '$root'
            echo '= 根目录内容 ='; ls -1d '$root'/* 2>&1 | head -6
            echo '= 各根下的 wx* 文件 ='; echo '$prefsInfo'
            echo '= 密码文件 ='; ls -l '$prefs' 2>&1
        """.trimIndent()
        val r = exec(cmd, 25)
        return (r.out + if (r.err.isNotBlank()) "\n[stderr] ${r.err}" else "").trim()
    }

    /**
     * 清掉历史误建的空目录。
     *
     * 早先按 /data/data/com.tencent.mm 拼路径写入配置时，`mkdir -p` 在
     * su 的 namespace 里凭空创建了 /data/user/0/com.tencent.mm，
     * 之后「目录存在」的判断会被它带偏。rmdir 只删空目录，安全。
     */
    fun cleanPhantom(): Boolean {
        val r = exec(
            "rmdir '/data/user/0/com.tencent.mm/files/wxbridge' " +
                "'/data/user/0/com.tencent.mm/files' '/data/user/0/com.tencent.mm' 2>/dev/null && echo 1 || echo 0",
            10
        )
        probeCache = null
        wxRootCached = null
        return r.out.trim().endsWith("1")
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

    /**
     * 列出目录下所有 .db 及其大小，按大小降序。
     *
     * 用 `stat -c` 而不是 `ls -l`：Android 不同 ROM 的 ls 输出列数不一致
     * （有的带 SELinux context），解析容易错位。
     */
    fun listDbFiles(dir: String): List<Pair<String, Long>> {
        val r = exec("stat -c '%s %n' '$dir'/*.db 2>/dev/null", 15)
        if (r.out.isBlank()) return emptyList()
        return r.out.lineSequence()
            .mapNotNull { line ->
                val sp = line.indexOf(' ')
                if (sp <= 0) return@mapNotNull null
                val size = line.substring(0, sp).trim().toLongOrNull() ?: return@mapNotNull null
                val name = line.substring(sp + 1).trim().substringAfterLast('/')
                if (name.isEmpty()) null else name to size
            }
            .sortedByDescending { it.second }
            .toList()
    }
}
