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
 * 好处：
 *   - 只依赖 com.tencent.wcdb（独立库，不参与微信混淆），跟已有的 openDatabase hook 同源
 *   - 零轮询，没消息时完全静默，不耗电
 *   - ContentValues 里直接带着 talker / type / isSend / content，连查库都省了
 *
 * 兜底：微信若走 execSQL 而非 insert，这里就收不到。所以 Engine 里保留了
 * 一个 60 秒的低频轮询，仅在 hook 完全没挂上时才启用。
 */
internal object NewMessageHook {

    private val INSERT_METHODS = listOf(
        "insert",
        "insertOrThrow",
        "insertWithOnConflict",
        "replace",
        "replaceOrThrow"
    )

    fun install(cl: ClassLoader, onMessage: (ContentValues) -> Unit): Boolean {
        val cls = runCatching {
            XposedHelpers.findClass("com.tencent.wcdb.database.SQLiteDatabase", cl)
        }.getOrNull()

        if (cls == null) {
            xlog("NewMessageHook: 找不到 SQLiteDatabase，无法监听新消息")
            return false
        }

        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                // insert(table, nullColumnHack, values, [conflictAlgorithm])
                val table = args.firstOrNull() as? String ?: return
                if (table != "message") return

                val values = args.filterIsInstance<ContentValues>().firstOrNull() ?: return
                runCatching { onMessage(values) }
                    .onFailure { xlog("NewMessageHook callback error: $it") }
            }
        }

        var hooked = 0
        for (name in INSERT_METHODS) {
            runCatching {
                XposedBridge.hookAllMethods(cls, name, callback)
                hooked++
            }.onFailure { xlog("NewMessageHook: hook $name failed: $it") }
        }

        xlog("NewMessageHook: 已 hook $hooked 个 insert 入口")
        return hooked > 0
    }
}
