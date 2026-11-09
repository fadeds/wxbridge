package io.wxbridge

import java.io.File
import java.security.SecureRandom

/**
 * 模块 ↔ App 的通道凭证。
 *
 * 广播的问题是 Android 不让两个普通 App 互相信任：`RECEIVER_EXPORTED` 之后，
 * 设备上任何一个 App 只要知道 action 名，就能发一条带任意 SQL 的 QUERY 把整个
 * 聊天库读走，或者注册同 action 的接收器监听每次回包。光靠「改成 LocalSocket」
 * 解决不了 —— 本地套接字同样是谁知道名字谁能连。
 *
 * 所以真正的防线是**共享密钥**：
 *   - token 落在微信自己的私有目录 `files/wxbridge/bridge.token`（目录 0700/0771，
 *     别的 App 连 stat 都会被拒），只有 root 和微信进程本身读得到；
 *   - 套接字名和广播请求都要求先出示 token，不知道 token 连上来也是白连。
 *
 * 谁能读这个文件？root。而本项目本来就重度依赖 root，等于是同一个信任级别，
 * 没有降低安全性。
 */
object BridgeAuth {

    const val DIR_NAME = "wxbridge"
    const val FILE_NAME = "bridge.token"

    /** 32 位十六进制 */
    private val TOKEN_RE = Regex("^[0-9a-f]{32}$")

    private val rnd = SecureRandom()

    /** 套接字名。从 token 派生，双方无需额外传参即可对上 */
    fun socketName(token: String): String = "wxbridge-" + token.take(12)

    fun isValid(token: String?): Boolean = token != null && TOKEN_RE.matches(token)

    fun newToken(): String {
        val b = ByteArray(16)
        rnd.nextBytes(b)
        // 不用 String.format("%02x", byte)：Byte 装箱后走 Java Formatter 的
        // 窄类型分支，行为虽然正确但依赖实现细节，手动补零更稳
        return b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    // ---------------- 微信进程内（模块侧）：直接读写自己的目录 ----------------

    fun fileIn(filesDir: File?): File? =
        filesDir?.let { File(File(it, DIR_NAME), FILE_NAME) }

    fun readFrom(filesDir: File?): String? {
        val f = fileIn(filesDir) ?: return null
        val t = runCatching { f.readText().trim() }.getOrNull()
        return if (isValid(t)) t else null
    }

    /**
     * 读不到就生成一个。模块比 App 先起来是常态（微信开机自启），
     * 所以两端都可能成为「第一个写的人」；并发写之后 token 可能不一致，
     * 由客户端握手失败时重读一次来兜底（见 ModuleBridge）。
     */
    fun ensureIn(filesDir: File?): String? {
        readFrom(filesDir)?.let { return it }
        val dir = filesDir?.let { File(it, DIR_NAME) } ?: return null
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            val t = newToken()
            val f = File(dir, FILE_NAME)
            // Android 应用进程的 umask 是 0077，建出来本身就是 0600，
            // 不用再 setReadable —— 反而会把手写的 0600 改坏
            f.writeText(t)
            t
        }.getOrNull()
    }

    // ---------------- App 侧：全部走 root ----------------

    @Volatile
    private var cached: String? = null

    fun forget() {
        cached = null
    }

    /** 读（带内存缓存）。阻塞，只能在 IO 线程调用 */
    fun tokenViaRoot(): String? {
        cached?.let { return it }
        val root = runCatching { RootShell.wxRoot() }.getOrNull() ?: return null

        RootShell.readText("$root/files/$DIR_NAME/$FILE_NAME")?.trim()?.let {
            if (isValid(it)) {
                cached = it
                return it
            }
        }
        if (!RootShell.hasRoot()) return null

        val token = newToken()
        val dir = "$root/files/$DIR_NAME"
        val file = "$dir/$FILE_NAME"
        val owner = "chown \$(stat -c '%u:%g' '$root')"
        val cmd = "mkdir -p '$dir' && " +
            "$owner '$dir' && chmod 771 '$dir' && " +
            "cat > '$file' && " +
            "$owner '$file' && chmod 660 '$file' && " +
            "(restorecon -R '$dir' 2>/dev/null || true) && " +
            "echo __OK__"
        val r = RootShell.execWithInput(cmd, token, 20)
        if (!r.out.contains("__OK__")) return null

        // 再读一次：并发情况下微信进程可能已经写了另一份，以落盘为准
        val landed = RootShell.readText(file)?.trim()
        val final = if (isValid(landed)) landed else token
        cached = final
        return final
    }
}
