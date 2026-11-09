package io.wxbridge.xposed

import android.net.LocalServerSocket
import android.net.LocalSocket
import io.wxbridge.BridgeAuth
import io.wxbridge.Wire
import org.json.JSONObject
import java.io.File

/**
 * 微信进程侧的本地套接字服务端。
 *
 * 取代原来的「导出广播」。广播有三个问题：
 *   1. **谁都能发**：`RECEIVER_EXPORTED` 且没有权限保护，同机任意 App 都能拿
 *      action 名去查库，也能监听回包。这是真实可用的攻击面，不只是理论风险。
 *   2. **谁都能收**：回包也是导出广播，聊天内容会流向任何注册了同一 action 的进程。
 *   3. **有 1MB 天花板**：Binder 事务限制逼得回包要 base64 + 120KB 分片，
 *      体积膨胀 33%，大结果集（几千条消息）要切几十个包，慢且容易乱序。
 *
 * 换成抽象命名空间的 LocalSocket 之后，走的是内存拷贝，没有体积限制、没有 base64。
 * 但套接字本身在抽象命名空间里同样是「知道名字就能连」，所以鉴权不能省：
 * 连上来必须先报 token（见 [BridgeAuth]），对不上直接断开。
 *
 * 为什么用抽象命名空间而不是文件套接字：文件套接字要落在某个目录里，
 * 而微信进程和 App 是不同的 UID，没有一个双方都能创建文件的公共目录
 * （/data/local/tmp 是 0711 shell:shell，两个 App 都写不进去）。
 */
internal object BridgeServer {

    /** 请求处理器，由 DataBridge 注入，避免两个 object 互相持有 */
    @Volatile
    var handler: ((JSONObject) -> Pair<Boolean, String>)? = null

    @Volatile
    private var filesDir: File? = null

    @Volatile
    private var server: LocalServerSocket? = null

    @Volatile
    private var boundName: String? = null

    @Volatile
    private var starting = false

    /** 给自检接口用 */
    val isUp: Boolean get() = server != null

    fun attach(dir: File?) {
        if (dir != null) filesDir = dir
        ensure()
    }

    /**
     * 没绑上不是错误：微信常常比 App 先起来，那时 token 还没有人写。
     * 所以这里只负责「没绑上就再试一次」，由 DataBridge 的看护线程周期触发。
     */
    fun ensure() {
        val dir = filesDir ?: return
        if (starting) return

        val current = BridgeAuth.readFrom(dir)
        if (server != null) {
            // 名字还是那个名字，什么都不用做
            if (current == null || BridgeAuth.socketName(current) == boundName) return
            // token 被另一端重新生成过（App 发现不一致会重写），旧套接字已经没人认了
            xlog("BridgeServer: token 变了，重新绑定")
            val old = server
            server = null
            boundName = null
            runCatching { old?.close() }
        }

        starting = true
        Thread {
            try {
                val token = BridgeAuth.ensureIn(dir) ?: return@Thread
                val name = BridgeAuth.socketName(token)
                runCatching { server?.close() }
                val s = LocalServerSocket(name)
                server = s
                boundName = name
                xlog("BridgeServer: 已监听本地套接字 $name")
                acceptLoop(s)
                // 走到这里说明 accept 挂了（套接字被关、微信进程回收），
                // 清掉标记让看护线程重新绑一次
                if (server === s) {
                    server = null
                    boundName = null
                }
            } catch (t: Throwable) {
                // 绑不上通常是 SELinux 或名字被旧进程占着，交给上层重试
                xlog("BridgeServer: 绑定失败 ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                starting = false
            }
        }.apply { isDaemon = true; name = "wxbridge-bridge" }.start()
    }

    private fun acceptLoop(s: LocalServerSocket) {
        while (server === s) {
            val sock = try {
                s.accept()
            } catch (t: Throwable) {
                if (server === s) xlog("BridgeServer: accept 中断 ${t.javaClass.simpleName}: ${t.message}")
                break
            }
            Thread { runCatching { handle(sock) }.onFailure { xlog("BridgeServer: $it") } }
                .apply { isDaemon = true; name = "wxbridge-conn" }
                .start()
        }
    }

    private fun handle(sock: LocalSocket) {
        try {
            sock.soTimeout = 20_000
            val ins = sock.inputStream
            val out = sock.outputStream

            // 1) 握手：WXB1 <token>
            val hello = Wire.readLine(ins) ?: return
            val parts = hello.split(' ')
            val token = if (parts.size == 2 && parts[0] == "WXB1") parts[1] else null
            val expect = BridgeAuth.readFrom(filesDir)
            if (token == null || expect == null || token != expect) {
                xlog("BridgeServer: 拒绝了一个 token 不匹配的连接")
                Wire.writeLine(out, "NO")
                return
            }
            Wire.writeLine(out, "OK")

            // 2) 单个请求 → 单个回包，然后关连接。调用方一次一个请求，
            //    长连接要保持状态反而更容易出错。
            val raw = Wire.readLine(ins) ?: return
            val req = runCatching { JSONObject(raw) }.getOrNull()
            if (req == null) {
                Wire.writeFrame(out, false, "请求不是合法 JSON")
                return
            }
            val h = handler
            if (h == null) {
                Wire.writeFrame(out, false, "服务端未就绪")
                return
            }
            val (ok, payload) = try {
                h(req)
            } catch (t: Throwable) {
                false to "${t.javaClass.simpleName}: ${t.message}"
            }
            Wire.writeFrame(out, ok, payload)
        } finally {
            runCatching { sock.close() }
        }
    }
}
