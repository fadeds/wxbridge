package io.wxbridge.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 发送入口探测。
 *
 * 现状：微信 8.0.79 的发送逻辑全在混淆包里（`ii5` / `ox0` / `yh0`），
 * `com.tencent.mm.modelmulti` 只剩 42 个壳类，没有 (String,String,int) 的静态方法。
 * 只能从调用栈里出现的类往下试。
 *
 * **必须挂 ClassLoader.loadClass**：微信开 Tinker 热修复，
 * 同一个类名在不同 ClassLoader 里是两个 Class 对象，
 * hook 主 loader 那份等于挂了个没人用的副本（这个坑已经踩过三次）。
 *
 * 纯诊断：只读、只打日志。
 */
internal object SendProbe {

    /** 栈里出现过的层，由近及远 */
    private val SUSPECT = listOf(
        "ox0.j0",
        "yh0.p"
    )

    private val hooked = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 总共只打这么多条命中，避免刷屏拖慢微信 */
    private var logsLeft = 24

    fun install(cl: ClassLoader) {
        // 覆盖 Tinker 的 DelegateLastClassLoader
        runCatching {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val c = param.result as? Class<*> ?: return
                    if (SUSPECT.contains(c.name)) hookClass(c)
                }
            })
        }.onFailure { xlog("SendProbe: hook loadClass 失败 $it") }

        for (name in SUSPECT) {
            runCatching { XposedHelpers.findClass(name, cl) }.getOrNull()?.let { hookClass(it) }
        }

        hookSendClick()
    }

    /**
     * 发送走的协程在存储层之前就切到了线程池，insert 的栈里看不到 UI。
     * 所以从另一头抓：hook 点击事件。
     *
     * 不猜类名 —— 前 6 次点击全部记录（类名 + id + 精简栈），
     * 这样即使发送按钮是自定义 View、名字里没有 Send，也能看到它。
     */
    private var clicksLeft = 6

    private fun hookSendClick() {
        runCatching {
            val cb = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (clicksLeft <= 0) return
                    val v = param.thisObject as? android.view.View ?: return
                    clicksLeft--
                    val cn = v.javaClass.name
                    xlog("SendProbe 点击[$clicksLeft]: $cn id=${v.id} tag=${runCatching { v.tag }.getOrNull()}")
                    Thread.currentThread().stackTrace.take(14).forEach { xlog("    at $it") }
                }
            }
            XposedBridge.hookAllMethods(android.view.View::class.java, "performClick", cb)
            XposedBridge.hookAllMethods(android.view.View::class.java, "callOnClick", cb)
            xlog("SendProbe: 已 hook View.performClick / callOnClick")
        }.onFailure { xlog("SendProbe: hook 点击失败 $it") }
    }

    private fun hookClass(c: Class<*>) {
        val key = "${c.name}@${System.identityHashCode(c.classLoader)}"
        if (!hooked.add(key)) return

        var n = 0
        for (m in c.declaredMethods) {
            if (n >= 30) break
            if (Modifier.isAbstract(m.modifiers)) continue
            // 只挂「带字符串参数、参数不多」的方法：发送必然要 talker 和 content
            val p = m.parameterTypes
            if (p.size < 2 || p.size > 8) continue
            if (p.none { it == String::class.java }) continue
            runCatching {
                XposedBridge.hookMethod(m, callback(c, m))
                n++
            }
        }
        if (n > 0) xlog("SendProbe: hooked ${c.name} ×$n (loader=${c.classLoader?.javaClass?.simpleName})")
    }

    private fun callback(c: Class<*>, m: Method) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (logsLeft <= 0) return
            logsLeft--
            val args = param.args.joinToString(" | ") { a ->
                when (a) {
                    null -> "null"
                    is String -> if (a.length > 40) "\"${a.take(40)}…\"" else "\"$a\""
                    else -> a.toString().take(40)
                }
            }
            xlog(
                "SendProbe 命中: ${c.name}#${m.name}" +
                    "(${m.parameterTypes.joinToString { it.simpleName }}) = $args"
            )
        }
    }
}
