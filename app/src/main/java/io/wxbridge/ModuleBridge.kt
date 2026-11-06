package io.wxbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/**
 * App 侧调用模块的客户端。
 *
 * 走广播而不是 root 读文件：
 * su 在 init 的 mount namespace 里看不到应用的 CE 存储，
 * 而模块本身就在微信进程里，手里有解密态的库实例 —— 让它查完直接广播回来最快。
 *
 * 注意：query() 会阻塞等待广播回来，**必须放在 IO 线程调用**。
 */
object ModuleBridge {

    private const val ACTION_REQ = "io.wxbridge.QUERY"
    private const val ACTION_RES = "io.wxbridge.RESULT"
    private const val WX_PKG = "com.tencent.mm"

    sealed class Result {
        data class Ok(val json: String) : Result()
        data class Fail(val reason: String) : Result()
    }

    /**
     * @param sql 空字符串表示自检：只问模块「桥通了吗、库拿到了吗」
     */
    fun query(ctx: Context, sql: String, timeoutMs: Long = 10_000): Result {
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
            ctx.sendBroadcast(
                Intent(ACTION_REQ)
                    .setPackage(WX_PKG)
                    .putExtra("reqId", reqId)
                    .putExtra("sql", sql)
            )
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return Result.Fail(
                    "模块没有回包（${timeoutMs}ms 超时）。\n" +
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
