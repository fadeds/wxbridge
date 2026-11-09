package io.wxbridge.xposed

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier

/**
 * 用「驱动微信自己的界面」来发送文本。
 *
 * 为什么不用发送 API：
 * 8.0.79 的发送逻辑在 `pluginsdk.ui.chat.z1#onClick` 里被丢进异步队列，
 * 真正的发送方法既不在调用栈上，也不在我们扫出来的 14 个候选里
 * （`va#a` 实测是语音转文字 diff，`ChatFooter#F0/N1/y` 一次都不触发）。
 * 微信还开着 Tinker，同一个类名在两套 ClassLoader 里是两个 Class，
 * 靠类名猜方法名这条路走不通。
 *
 * 界面驱动的优点：走的是微信自己的代码路径，版本升级不受影响；
 * 缺点：必须有一个聊天界面在前台。
 */
internal object WxUiSender {

    private const val FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"

    /** 发送按钮的 id，来自点击探测日志（android.widget.Button id=2131366064） */
    private const val SEND_BUTTON_ID = 2131366064

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var footerClass: Class<*>? = null

    @Volatile
    private var footer: Any? = null

    /** 只 dump 一次字段，用于确认结构 */
    private var dumped = false

    fun install(cl: ClassLoader) {
        runCatching {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val c = param.result as? Class<*> ?: return
                    if (c.name == FOOTER) hookFooterCtor(c)
                }
            })
        }.onFailure { xlog("WxUiSender: hook loadClass 失败 $it") }

        runCatching { XposedHelpers.findClass(FOOTER, cl) }
            .getOrNull()
            ?.let { hookFooterCtor(it) }
    }

    /** 抓 ChatFooter 实例：每次打开聊天都会新建一个 */
    private fun hookFooterCtor(c: Class<*>) {
        if (footerClass != null) return
        footerClass = c
        var n = 0
        for (ctor in c.declaredConstructors) {
            runCatching {
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val obj = param.thisObject ?: return
                        footer = obj
                        xlog("WxUiSender: 捕获 ChatFooter 实例")
                        if (!dumped) {
                            dumped = true
                            dumpFields(obj)
                        }
                    }
                })
                n++
            }
        }
        if (n > 0) xlog("WxUiSender: 已 hook ChatFooter 构造 ×$n")
    }

    private fun dumpFields(obj: Any) {
        runCatching {
            var f: Class<*>? = obj.javaClass
            var count = 0
            while (f != null && count < 40) {
                for (field in f.declaredFields) {
                    if (count >= 40) break
                    if (Modifier.isStatic(field.modifiers)) continue
                    field.isAccessible = true
                    val v = runCatching { field.get(obj) }.getOrNull() ?: continue
                    val t = field.type
                    // 只关心输入框和按钮
                    if (t == EditText::class.java || t.name.contains("EditText") ||
                        t == View::class.java || t.name.contains("Button")
                    ) {
                        xlog("WxUiSender 字段: ${field.name}: ${t.name} = ${v.javaClass.name}")
                        count++
                    }
                }
                f = f.superclass
            }
        }.onFailure { xlog("WxUiSender: dump 失败 ${it.message}") }
    }

    /**
     * 把文本写进当前聊天输入框并点发送。
     *
     * @return null 表示成功发起，非 null 是失败原因
     */
    fun send(text: String): String? {
        val f = footer ?: return "没有捕获到 ChatFooter —— 先在微信里打开一个聊天"
        val activity = currentActivity() ?: return "拿不到当前 Activity"

        val edit = findEditText(f) ?: return "ChatFooter 里找不到输入框"
        val act = activity

        var done = false
        var err: String? = null

        main.post {
            runCatching {
                edit.setText(text)
                edit.setSelection(edit.text?.length ?: 0)
                val btn = act.window?.decorView?.findViewById<View>(SEND_BUTTON_ID)
                    ?: findClickableButton(f)
                if (btn == null) {
                    err = "找不到发送按钮"
                    return@runCatching
                }
                btn.performClick()
                done = true
            }.onFailure { err = "${it.javaClass.simpleName}: ${it.message}" }
        }

        // 等 UI 线程跑完（最多 3 秒）
        val deadline = System.currentTimeMillis() + 3000
        while (!done && err == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        return when {
            err != null -> err
            done -> null
            else -> "UI 线程超时"
        }
    }

    private fun findEditText(obj: Any): EditText? {
        var f: Class<*>? = obj.javaClass
        while (f != null) {
            for (field in f.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (!EditText::class.java.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                (runCatching { field.get(obj) }.getOrNull() as? EditText)?.let { return it }
            }
            f = f.superclass
        }
        return null
    }

    private fun findClickableButton(obj: Any): View? {
        var f: Class<*>? = obj.javaClass
        while (f != null) {
            for (field in f.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (!View::class.java.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                val v = runCatching { field.get(obj) }.getOrNull() as? View ?: continue
                if (v.id == SEND_BUTTON_ID) return v
            }
            f = f.superclass
        }
        return null
    }

    private fun currentActivity(): Activity? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val current = XposedHelpers.callStaticMethod(at, "currentActivityThread")
        val list = XposedHelpers.getObjectField(current, "mActivities") as? Map<*, *>
            ?: return null
        for (v in list.values) {
            if (v == null) continue
            val act = XposedHelpers.getObjectField(v, "activity") as? Activity
            if (act != null && !act.isFinishing) return act
        }
        null
    }.getOrNull()
}
