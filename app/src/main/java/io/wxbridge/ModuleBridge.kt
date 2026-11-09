package io.wxbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * App 侧调用模块的客户端。
 *
 * 传输层和模块侧对称：**本地套接字优先，广播兜底**，两条通道都要带 token。
 *
 * 上一版只有广播，而且是 `RECEIVER_EXPORTED` 且无权限保护 —— 同机任意 App
 * 都能拿 action 名查走整个聊天库，也能监听回包。现在 token 只有 root 读得到，
 * 攻击者连不上也发不动。
 *
 * 注意：这些方法都会阻塞等待回包，**必须放在 IO 线程调用**（内部有 su 调用）。
 */
object ModuleBridge {

    private const val ACTION_REQ = "io.wxbridge.QUERY"
    private const val ACTION_SEND = "io.wxbridge.SEND"
    private const val ACTION_RES = "io.wxbridge.RESULT"
    private const val WX_PKG = "com.tencent.mm"

    sealed class Result {
        data class Ok(val json: String) : Result()
        data class Fail(val reason: String) : Result()
    }

    /** 上一次实际走通的通道，界面直接显示，排查「到底走的哪条路」很省事 */
    @Volatile
    var lastChannel: String = "未连接"
        private set

    /** 查库。sql 为空表示自检：只问模块「桥通了吗、库拿到了吗」 */
    fun query(ctx: Context, sql: String, timeoutMs: Long = 10_000): Result =
        call(ctx, timeoutMs, "query", sql, null, null)

    /** 发一条文本消息。talker 是 wxid 或群 id */
    fun send(ctx: Context, talker: String, text: String, timeoutMs: Long = 12_000): Result =
        call(ctx, timeoutMs, "send", "", talker, text)

    // ---------------- 主流程 ----------------

    private fun call(
        ctx: Context,
        timeoutMs: Long,
        op: String,
        sql: String,
        talker: String?,
        text: String?
    ): Result {
        val req = JSONObject().apply {
            put("op", op)
            put("sql", sql)
            if (talker != null) put("talker", talker)
            if (text != null) put("text", text)
        }

        // 这里刻意不用「先判空、再赋值」那套写法：
        // 编译器在这条路径上没把 first 推断成非空，试了一次仍然报
        // "inferred type is String? but String was expected"。
        // 用 elvis + run 直接产出非空值，类型是确定的，不需要任何智能转换。
        var token: String = BridgeAuth.tokenViaRoot() ?: run {
            lastChannel = "无凭证"
            return Result.Fail(
                "拿不到通道凭证。\n" +
                    "→ token 落在微信私有目录，只有 root 读得到，先点「① 环境自检」确认 su 可用\n" +
                    "→ 微信至少要启动过一次，模块才有机会写这个文件"
            )
        }

        // 第一次走主通道
        val first = trySocket(token, req, timeoutMs)
        if (first is SocketTry.Done) {
            lastChannel = "本地套接字"
            return first.result
        }

        // 鉴权失败：两端可能各写了一份 token（微信比 App 先启动是常态），重读一次再试。
        // 顺带清掉微信路径缓存 —— /proc/<pid>/root 里的 pid 一旦换了，
        // 读文件会静默失败，看起来像是 token 变了
        if (first is SocketTry.BadToken) {
            BridgeAuth.forget()
            RootShell.resetPathCache()
            token = BridgeAuth.tokenViaRoot() ?: run {
                lastChannel = "无凭证"
                return Result.Fail("通道 token 重读失败，检查 root 权限后重开微信")
            }
            val retry = trySocket(token, req, timeoutMs)
            if (retry is SocketTry.Done) {
                lastChannel = "本地套接字"
                return retry.result
            }
        }

        lastChannel = "广播兜底"
        return broadcastCall(ctx, token, req, timeoutMs)
    }

    // ---------------- 主通道：本地套接字 ----------------

    private sealed class SocketTry {
        data class Done(val result: Result) : SocketTry()

        /** 连不上：服务端没起、SELinux 拦截、模块未加载……都不算业务错误，直接兜底 */
        object Unavailable : SocketTry()

        /** 对端在，但 token 对不上 */
        object BadToken : SocketTry()
    }

    private fun trySocket(token: String, req: JSONObject, timeoutMs: Long): SocketTry {
        val sock = LocalSocket()
        return try {
            sock.connect(LocalSocketAddress(BridgeAuth.socketName(token)))
            sock.soTimeout = timeoutMs.toInt()
            val ins = sock.inputStream
            val out = sock.outputStream

            Wire.writeLine(out, "WXB1 $token")
            when (Wire.readLine(ins)) {
                null -> SocketTry.Unavailable
                "OK" -> {
                    Wire.writeLine(out, req.toString())
                    val frame = Wire.readFrame(ins)
                    when {
                        frame == null -> SocketTry.Done(Result.Fail("本地通道断了，没读到回包"))
                        frame.first -> SocketTry.Done(Result.Ok(frame.second))
                        else -> SocketTry.Done(Result.Fail(frame.second))
                    }
                }
                "NO" -> SocketTry.BadToken
                else -> SocketTry.Unavailable
            }
        } catch (t: Throwable) {
            // 连不上/超时/被拒绝都视为「这条通道不可用」，不要把异常抛给界面
            SocketTry.Unavailable
        } finally {
            runCatching { sock.close() }
        }
    }

    // ---------------- 兜底通道：带 token 的广播 ----------------

    private fun broadcastCall(
        ctx: Context,
        token: String,
        req: JSONObject,
        timeoutMs: Long
    ): Result {
        val reqId = UUID.randomUUID().toString()
        val chunks = TreeMap<Int, String>()
        val latch = CountDownLatch(1)
        // 局部变量不能标 @Volatile，用原子类型跨线程传递结果
        val total = AtomicInteger(-1)
        val error = AtomicReference("")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                if (intent.getStringExtra("reqId") != reqId) return

                val err = intent.getStringExtra("error") ?: ""
                if (err.isNotEmpty()) {
                    error.set(err)
                    latch.countDown()
                    return
                }
                chunks[intent.getIntExtra("seq", 0)] = intent.getStringExtra("data") ?: ""
                total.set(intent.getIntExtra("total", 0))
                if (total.get() > 0 && chunks.size >= total.get()) latch.countDown()
            }
        }

        val filter = IntentFilter(ACTION_RES)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }

        return try {
            val intent = Intent()
                .setPackage(WX_PKG)
                .putExtra("reqId", reqId)
                .putExtra("token", token)
            if (req.optString("op") == "send") {
                intent.action = ACTION_SEND
                intent.putExtra("talker", req.optString("talker"))
                intent.putExtra("text", req.optString("text"))
            } else {
                intent.action = ACTION_REQ
                intent.putExtra("sql", req.optString("sql"))
            }
            ctx.sendBroadcast(intent)

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return Result.Fail(
                    "模块没有回包（${timeoutMs}ms 超时，两条通道都没通）。\n" +
                        "→ 微信进程要在跑\n" +
                        "→ LSPosed 里模块要启用、作用域勾微信\n" +
                        "→ 装新版本后必须强制停止微信再打开"
                )
            }
            if (error.get().isNotEmpty()) return Result.Fail(error.get())

            val b64 = buildString {
                for ((_, v) in chunks) append(v)
            }
            val json = String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)
            Result.Ok(json)
        } catch (t: Throwable) {
            Result.Fail("${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }
}
