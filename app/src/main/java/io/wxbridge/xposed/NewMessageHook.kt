package io.wxbridge.xposed

import android.content.ContentValues
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 新消息事件源。**替代轮询**。
 *
 * 思路：不猜微信那些被混淆成单字母的消息回调类，而是下沉到存储层 ——
 * 不管消息从哪条链路进来，最后都要写进 `message` 表。
 * 于是 hook WCDB 的 insert 系列，只认表名 == "message"。
 *
 * 和 WcdbKeyHook 一样，必须挂 ClassLoader.loadClass 才能覆盖微信的
 * Tinker 热修复类加载器，否则 hook 的是没人用的那份 Class。
 */
internal object NewMessageHook {

    private const val DB_CLASS = "com.tencent.wcdb.database.SQLiteDatabase"

    private val INSERT_METHODS = listOf(
        "insert",
        "insertOrThrow",
        "insertWithOnConflict",
        "replace",
        "replaceOrThrow"
    )

    private val hooked = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 触发计数，前几次打日志确认 insert 真的被调用 */
    private var hits = 0

    fun install(cl: ClassLoader, onMessage: (ContentValues) -> Unit): Boolean {
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                // insert(table, nullColumnHack, values, [conflictAlgorithm])
                val table = args.firstOrNull() as? String ?: return
                if (table != "message") return

                val values = args.filterIsInstance<ContentValues>().firstOrNull() ?: return

                if (hits < 5) {
                    hits++
                    val talker = runCatching { values.getAsString("talker") }.getOrNull()
                    xlog("message insert #$hits talker=$talker")
                }

                runCatching { onMessage(values) }
                    .onFailure { xlog("NewMessageHook callback error: $it") }
            }
        }

        fun hookOn(c: Class<*>) {
            val key = "${c.name}@${System.identityHashCode(c.classLoader)}"
            if (!hooked.add(key)) return

            var n = 0
            for (name in INSERT_METHODS) {
                runCatching {
                    XposedBridge.hookAllMethods(c, name, callback)
                    n++
                }
            }
            if (n > 0) {
                xlog("NewMessageHook: hooked $DB_CLASS ×$n (loader=${c.classLoader?.javaClass?.simpleName})")
            }
        }

        // 覆盖 Tinker 的 DelegateLastClassLoader
        runCatching {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val c = param.result as? Class<*> ?: return
                    if (c.name == DB_CLASS) hookOn(c)
                }
            })
        }.onFailure { xlog("NewMessageHook: hook loadClass failed: $it") }

        // 主 ClassLoader 里已有的
        runCatching { XposedHelpers.findClass(DB_CLASS, cl) }
            .getOrNull()
            ?.let { hookOn(it) }

        return hooked.isNotEmpty()
    }
}
