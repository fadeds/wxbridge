package io.wxbridge.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.os.Build
import android.util.Base64
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier
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
    const val ACTION_SEND = "io.wxbridge.SEND"
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

    /** 微信的 ClassLoader，反射找发送类要用 */
    @Volatile
    private var cl: ClassLoader? = null

    fun install(classLoader: ClassLoader) {
        cl = classLoader
    }

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

    /** 供自动回复引擎在微信进程内直接查库 */
    fun currentDb(): Any? = db

    /** 主库文件路径（SQLiteDatabase.getPath()），用来定位 MicroMsg 目录、表情包等 */
    fun dbPath(): String? {
        val d = db ?: return null
        return runCatching {
            d.javaClass.methods.firstOrNull { it.name == "getPath" && it.parameterCount == 0 }
                ?.invoke(d) as? String
        }.getOrNull()
    }

    private fun registerIfNeeded() {
        val c = ctx ?: return
        if (registered) return
        synchronized(this) {
            if (registered) return
            runCatching {
                val filter = IntentFilter(ACTION_REQ).apply { addAction(ACTION_SEND) }
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
                    payload = if (intent?.action == ACTION_SEND) {
                        sendText(
                            intent.getStringExtra("talker") ?: "",
                            intent.getStringExtra("text") ?: ""
                        )
                    } else if (sql.isBlank()) info() else runQuery(sql)
                } catch (t: Throwable) {
                    error = "${t.javaClass.simpleName}: ${t.message}"
                }
                reply(reqId, payload, error)
            }.apply { isDaemon = true }.start()
        }
    }

    /**
     * 发一条文本。
     *
     * 候选来自包扫描：`com.tencent.mm.pluginsdk.ui.chat.va#a(String, String)` 是静态方法，
     * 不需要 ChatFooter 实例，最适合直接调用。参数顺序按 (talker, content) 猜的先试，
     * 失败再把两者对调一次 —— 这样一次调用就能确定顺序。
     */
    /**
     * 发一条文本。
     *
     * 不再调用微信的发送 API：8.0.79 里它在 `z1#onClick` 内被丢进异步队列，
     * 方法名混淆 + Tinker 双 ClassLoader，靠类名反射不稳定。
     * 改成驱动微信自己的界面（写输入框 + 点发送按钮），版本无关。
     */
    private fun sendText(talker: String, text: String): String {
        val o = JSONObject()
        o.put("talker", talker)
        o.put("text", text)
        // 优先走微信自己的网络层（DexKit 定位到的 NetSceneSendMsg）
        val apiErr = WxSendApi.send(talker, text)
        if (apiErr == null) {
            o.put("method", "netscene")
            return o.toString()
        }
        // 兜底：驱动界面
        val uiErr = WxUiSender.send(text)
        o.put("apiError", apiErr)
        if (uiErr == null) o.put("method", "ui-footer") else o.put("error", uiErr)
        return o.toString()
    }

    /** 空 SQL 表示「自检」：告诉 App 桥通了没、库有没有、有哪些表 */
    private fun info(): String {
        val o = JSONObject()
        o.put("bridge", true)
        o.put("sendApi", WxSendApi.status)
        val d = db
        o.put("dbReady", d != null)
        if (d != null) {
            o.put("dbClass", d.javaClass.name)
            runCatching {
                val all = queryRows(d, "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
                val names = (0 until all.length()).map { all.getJSONObject(it).optString("name") }
                o.put("tableCount", names.size)
                // 只回与消息/联系人/图片相关的，别把几百张表全塞进广播
                o.put(
                    "tables",
                    JSONArray(
                        names.filter {
                            it.contains("message", true) || it.contains("contact", true) ||
                                it.contains("img", true) || it.contains("emoji", true)
                        }
                    )
                )
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
                    // 不能一律 getString：message 表里有 BLOB 字段（如 lvbuffer），
                    // 直接 getString 会抛 "Unable to convert BLOB to string"
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> row.put(cols[i], JSONObject.NULL)
                        Cursor.FIELD_TYPE_BLOB ->
                            row.put(cols[i], "<blob:${c.getBlob(i)?.size ?: 0}>")
                        else -> row.put(cols[i], c.getString(i) ?: "")
                    }
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
