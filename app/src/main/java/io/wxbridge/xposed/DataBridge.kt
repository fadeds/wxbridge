package io.wxbridge.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.os.Build
import android.util.Base64
import io.wxbridge.BridgeAuth
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块 ↔ App 的数据桥。
 *
 * 为什么不让 App 用 su 直接读微信目录？那条路已经被证明走不通：su 跑在 init 的
 * mount namespace 里，看不到 app 的 CE 存储，`/proc/<pid>/root` 借 namespace 也时通时不通。
 * 而这个模块本来就在微信进程里，手里有**解密态的 SQLiteDatabase 实例**，
 * 查完直接把结果发出去最快。
 *
 * 传输层现在是**本地套接字优先，广播兜底**：
 *
 *   - 主通道：[BridgeServer]，抽象命名空间 LocalSocket，先验 token 再放行。
 *     没有 Binder 的 1MB 上限，正文直接 UTF-8，不用 base64 分片。
 *   - 兜底：老的显式广播。保留它是因为抽象命名空间的跨 UID 连接在部分 ROM 上
 *     会被 SELinux 拦掉 —— 真被拦了也不至于整个功能不可用。
 *     **兜底路径同样要求 token**，否则只是把一个洞换了个地方留。
 *
 * 协议（两条通道共用同一套语义）：
 *   请求  { op: "query"|"send", sql?, talker?, text? }
 *   成功  正文 = JSON
 *   失败  正文 = 错误描述
 */
internal object DataBridge {

    /** 兜底广播的 action。主通道换掉了，这几个常量只为兼容保留 */
    const val ACTION_REQ = "io.wxbridge.QUERY"
    const val ACTION_SEND = "io.wxbridge.SEND"
    const val ACTION_RES = "io.wxbridge.RESULT"

    private const val APP_PKG = "io.wxbridge"
    private const val CHUNK = 120_000
    /** 看护间隔。只做一次文件 stat + 可能的重新绑定，开销可以忽略 */
    private const val WATCHDOG_GAP_MS = 60_000L

    @Volatile
    private var ctx: Context? = null

    /** 消息主库实例。来自 NewMessageHook 的 insert 回调（param.thisObject） */
    @Volatile
    private var db: Any? = null

    @Volatile
    private var registered = false

    @Volatile
    private var watchdogStarted = false

    /** 微信的 ClassLoader，反射找发送类要用 */
    @Volatile
    private var cl: ClassLoader? = null

    fun install(classLoader: ClassLoader) {
        cl = classLoader
        // 套接字服务端不直接查库，通过这个回调回到本 object，
        // 免得两个 object 互相静态持有
        BridgeServer.handler = ::handle
    }

    fun attach(c: Context) {
        val app = c.applicationContext ?: c
        ctx = app
        registerLegacyReceiver()
        BridgeServer.attach(app.filesDir)
        startWatchdog()
    }

    /**
     * 收到 message 表的 insert 时，`param.thisObject` 就是那条消息所属的
     * SQLiteDatabase —— 不需要密码、不需要复制文件，直接查。
     */
    fun provideDb(instance: Any) {
        if (db == null) xlog("DataBridge: 捕获消息库实例 ${instance.javaClass.name}")
        db = instance
        registerLegacyReceiver()
        BridgeServer.ensure()
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

    // ---------------- 请求分发 ----------------

    private fun handle(req: JSONObject): Pair<Boolean, String> = try {
        when (val op = req.optString("op", "query")) {
            "ping", "query" -> {
                val sql = req.optString("sql", "")
                true to (if (sql.isBlank()) info() else runQuery(sql))
            }
            "send" -> true to sendText(req.optString("talker"), req.optString("text"))
            else -> false to "未知 op: $op"
        }
    } catch (t: Throwable) {
        false to "${t.javaClass.simpleName}: ${t.message}"
    }

    // ---------------- 兜底广播（带 token 鉴权） ----------------

    private fun registerLegacyReceiver() {
        val c = ctx ?: return
        if (registered) return
        synchronized(this) {
            if (registered) return
            runCatching {
                val filter = IntentFilter(ACTION_REQ).apply { addAction(ACTION_SEND) }
                if (Build.VERSION.SDK_INT >= 33) {
                    c.registerReceiver(legacyReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    c.registerReceiver(legacyReceiver, filter)
                }
                registered = true
                xlog("DataBridge: 兜底广播已注册（需带 token）")
            }.onFailure { xlog("DataBridge: registerReceiver 失败 $it") }
        }
    }

    private val legacyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val reqId = intent?.getStringExtra("reqId") ?: return

            val expect = BridgeAuth.readFrom(ctx?.filesDir)
            if (expect == null || intent.getStringExtra("token") != expect) {
                // 不知道 token 的进程一律当攻击者处理，不回任何数据。
                // 这条日志是审计用的：真有人在本机试探，LSPosed 日志里能看见
                xlog("DataBridge: 拒绝了一个不带有效 token 的请求（${intent.action}）")
                reply(reqId, "", "通道鉴权失败：token 不匹配")
                return
            }

            val req = JSONObject().apply {
                put("op", if (intent.action == ACTION_SEND) "send" else "query")
                put("sql", intent.getStringExtra("sql") ?: "")
                put("talker", intent.getStringExtra("talker") ?: "")
                put("text", intent.getStringExtra("text") ?: "")
            }

            // 查询可能耗时，绝不能占着广播线程
            Thread {
                val (ok, payload) = handle(req)
                reply(reqId, if (ok) payload else "", if (ok) null else payload)
            }.apply { isDaemon = true }.start()
        }
    }

    /**
     * 套接字看护：长期低频重试。
     *
     * 不只是「首次绑不上」时有用 —— token 被另一端重写、accept 线程意外挂掉，
     * 都要靠它把服务重新拉起来。所以它不会自己退出。
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        Thread {
            var warned = false
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_GAP_MS)
                    BridgeServer.ensure()
                    if (!warned && !BridgeServer.isUp) {
                        warned = true
                        xlog("BridgeServer: 一直没绑上，走广播兜底（功能可用，但体积和速度都差一些）")
                    }
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    // 看护线程自己绝对不能死，否则通道再也起不来
                    xlog("DataBridge: watchdog 异常 ${t.javaClass.simpleName}")
                }
            }
        }.apply { isDaemon = true; name = "wxbridge-watchdog" }.start()
    }

    // ---------------- 业务 ----------------

    /**
     * 发一条文本。
     *
     * 不再调用微信的发送 API：8.0.79 里它在 `z1#onClick` 内被丢进异步队列，
     * 方法名混淆 + Tinker 双 ClassLoader，靠类名反射不稳定。
     * 优先走 DexKit 定位到的 NetSceneSendMsg，失败再驱动微信自己的界面
     * （写输入框 + 点发送按钮），版本升级不受影响。
     */
    private fun sendText(talker: String, text: String): String {
        val o = JSONObject()
        o.put("talker", talker)
        o.put("text", text)
        val apiErr = WxSendApi.send(talker, text)
        if (apiErr == null) {
            o.put("method", "netscene")
            return o.toString()
        }
        val uiErr = WxUiSender.send(text)
        o.put("apiError", apiErr)
        if (uiErr == null) o.put("method", "ui-footer") else o.put("error", uiErr)
        return o.toString()
    }

    /** 空 SQL 表示「自检」：告诉 App 桥通了没、库有没有、有哪些表 */
    private fun info(): String {
        val o = JSONObject()
        o.put("bridge", true)
        o.put("socketUp", BridgeServer.isUp)
        o.put("sendApi", WxSendApi.status)

        val app = runCatching { android.app.AndroidAppHelper.currentApplication() }.getOrNull()
        val ver = runCatching {
            app?.packageManager?.getPackageInfo(WX_PKG, 0)?.versionName
        }.getOrNull()
        o.put("wxVersion", ver ?: "")

        val d = db
        o.put("dbReady", d != null)
        if (d != null) {
            o.put("dbClass", d.javaClass.name)
            runCatching {
                val all = queryRows(d, "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
                val names = (0 until all.length()).map { all.getJSONObject(it).optString("name") }
                o.put("tableCount", names.size)
                // 只回与消息/联系人/图片相关的，别把几百张表全塞回去
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

    // ---------------- 兜底广播的回包（仍需分片） ----------------

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
