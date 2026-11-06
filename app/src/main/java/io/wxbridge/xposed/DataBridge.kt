package io.wxbridge.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块 ↔ App 的数据桥。
 *
 * 为什么用广播而不是「App 用 su 读微信目录」？
 * 那条路已经被证明走不通：su 跑在 init 的 mount namespace，看不到 app 的 CE 存储，
 * `/proc/<pid>/root` 借 namespace 也时通时不通。而这个模块本来就在微信进程里，
 * 手里有**解密态的 SQLiteDatabase 实例**，查完直接把结果发出去最快。
 *
 * 参考 duqian291902259/WechatHook 的做法：数据不出微信进程，只用广播搬运。
 *
 * 协议：
 *   App  → 微信:  action=io.wxbridge.QUERY  { reqId, sql }
 *   微信 → App:   action=io.wxbridge.RESULT { reqId, seq, total, data(base64), error }
 *
 * 分片是因为广播走 Binder，单次大约 1MB 上限。每片 120KB base64，够用且安全。
 */
internal object DataBridge {

    const val ACTION_REQ = "io.wxbridge.QUERY"
    const val ACTION_RES = "io.wxbridge.RESULT"

    private const val APP_PKG = "io.wxbridge"
    private const val CHUNK = 120_000

    @Volatile
    private var ctx: Context? = null

    /** 消息主库实例。来自 NewMessageHook 的 insert 回调（param.thisObject） */
    @Volatile
    private var db: Any? = null

    @Volatile
    private var registered = false

    fun attach(c: Context) {
        ctx = c.applicationContext ?: c
        registerIfNeeded()
    }

    /**
     * 收到 message 表的 insert 时，`param.thisObject` 就是那条消息所属的
     * SQLiteDatabase —— 不需要密码、不需要复制文件，直接查。
     */
    fun provideDb(instance: Any) {
        if (db == null) xlog("DataBridge: 捕获消息库实例 ${instance.javaClass.name}")
        db = instance
        registerIfNeeded()
    }

    private fun registerIfNeeded() {
        val c = ctx ?: return
        if (registered) return
        synchronized(this) {
            if (registered) return
            runCatching {
                val filter = IntentFilter(ACTION_REQ)
                if (Build.VERSION.SDK_INT >= 33) {
                    c.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    c.registerReceiver(receiver, filter)
                }
                registered = true
                xlog("DataBridge: 查询监听已注册")
            }.onFailure { xlog("DataBridge: registerReceiver 失败 $it") }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val reqId = intent?.getStringExtra("reqId") ?: return
            val sql = intent.getStringExtra("sql") ?: ""
            // 查询可能耗时，绝不能占着广播线程
            Thread {
                var error: String? = null
                var payload = ""
                try {
                    payload = if (sql.isBlank()) info() else runQuery(sql)
                } catch (t: Throwable) {
                    error = "${t.javaClass.simpleName}: ${t.message}"
                }
                reply(reqId, payload, error)
            }.apply { isDaemon = true }.start()
        }
    }

    /** 空 SQL 表示「自检」：告诉 App 桥通了没、库有没有、有哪些表 */
    private fun info(): String {
        val o = JSONObject()
        o.put("bridge", true)
        val d = db
        o.put("dbReady", d != null)
        if (d != null) {
            o.put("dbClass", d.javaClass.name)
            runCatching {
                o.put("tables", queryRows(d, "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"))
            }.onFailure { o.put("tablesError", it.message ?: it.javaClass.simpleName) }
        }
        return o.toString()
    }

    private fun runQuery(sql: String): String {
        val d = db ?: throw IllegalStateException("还没捕获到消息库实例 —— 先在微信里收/发一条消息")
        return queryRows(d, sql).toString()
    }

    private fun queryRows(d: Any, sql: String): JSONArray {
        val m = d.javaClass.methods.firstOrNull {
            it.name == "rawQuery" && it.parameterTypes.size == 2 && it.parameterTypes[1].isArray
        } ?: throw IllegalStateException("找不到 rawQuery(String, String[])")

        val cursor = m.invoke(d, sql, emptyArray<String>()) as? Cursor
            ?: throw IllegalStateException("rawQuery 返回 null")

        val arr = JSONArray()
        cursor.use { c ->
            val cols = c.columnNames
            while (c.moveToNext()) {
                val row = JSONObject()
                for (i in cols.indices) {
                    // 统一转字符串：BLOB 用 getString 会抛，所以先判类型
                    row.put(cols[i], if (c.isNull(i)) "" else c.getString(i))
                }
                arr.put(row)
            }
        }
        return arr
    }

    private fun reply(reqId: String, payload: String, error: String?) {
        val c = ctx ?: return
        runCatching {
            val b64 = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val total = if (b64.isEmpty()) 1 else (b64.length + CHUNK - 1) / CHUNK
            for (i in 0 until total) {
                val from = i * CHUNK
                val to = minOf(b64.length, from + CHUNK)
                val part = if (b64.isEmpty()) "" else b64.substring(from, to)
                c.sendBroadcast(
                    Intent(ACTION_RES)
                        .setPackage(APP_PKG)
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        .putExtra("reqId", reqId)
                        .putExtra("seq", i)
                        .putExtra("total", total)
                        .putExtra("data", part)
                        .putExtra("error", error ?: "")
                )
            }
        }.onFailure { xlog("DataBridge: 回包失败 $it") }
    }
}
