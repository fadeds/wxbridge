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

    /**
     * 需要挂的发送相关类 → 方法名。
     *
     * 必须按「类真正被加载时」挂：微信开 Tinker，
     * findClass(name, 主 ClassLoader) 拿到的是没人用的那份 Class，
     * 日志显示 hook 成功但一次都不触发（已经踩过四次）。
     */
    private val WATCH: Map<String, List<String>> = mapOf(
        "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" to listOf("F0", "N1", "y"),
        "com.tencent.mm.pluginsdk.ui.chat.va" to listOf("a"),
        "com.tencent.mm.pluginsdk.ui.chat.z1" to listOf("onClick"),
        "com.tencent.mm.pluginsdk.ui.chat.q2" to listOf("onClick")
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
                    WATCH[c.name]?.let { names -> hookNamedOn(c, names) }
                }
            })
        }.onFailure { xlog("SendProbe: hook loadClass 失败 $it") }

        for (name in SUSPECT) {
            runCatching { XposedHelpers.findClass(name, cl) }.getOrNull()?.let { hookClass(it) }
        }

        hookSendClick()
        hookSendButtonListener(cl)
        scanChatPackage(cl)
        hookSendCandidates(cl)
    }

    /**
     * 从点击探测里确认的事实：
     *   发送按钮 = android.widget.Button id=2131366064
     *   监听器   = com.tencent.mm.pluginsdk.ui.chat.z1
     *
     * `pluginsdk` 这个包名没有被混淆（不是单字母），比 `ii5`/`ox0` 稳定得多。
     * 直接挂它的 onClick，把 40 帧栈打出来，发送 API 一定在里面。
     */
    private fun hookSendButtonListener(cl: ClassLoader) {
        hookOnClickByName("com.tencent.mm.pluginsdk.ui.chat.z1", cl, 40)
        // 顺带把旁边那个按钮也挂上（+ / 表情），便于区分
        hookOnClickByName("com.tencent.mm.pluginsdk.ui.chat.q2", cl, 16)
    }

    private fun hookOnClickByName(name: String, cl: ClassLoader, frames: Int) {
        runCatching {
            val c = XposedHelpers.findClass(name, cl)
            var n = 0
            for (m in c.declaredMethods) {
                if (m.name != "onClick" || m.parameterCount != 1) continue
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (onClickLogsLeft <= 0) return
                        onClickLogsLeft--
                        xlog("SendProbe SEND onClick: ${c.name}#onClick")
                        dumpListenerFields(param.thisObject)
                    }
                })
                n++
            }
            if (n > 0) xlog("SendProbe: 已 hook 发送监听器 $name ×$n")
        }.onFailure { xlog("SendProbe: hook $name 失败 ${it.message}") }
    }

    private var onClickLogsLeft = 3

    /**
     * 扫 `com.tencent.mm.pluginsdk.ui.chat` 包。
     *
     * 这个包名没有被混淆（不是单字母），发送相关的类很可能都在里面。
     * 同时列出「带 2 个以上 String 参数」的方法 —— 发送必然要 talker + content。
     */
    private fun scanChatPackage(cl: ClassLoader) {
        val names = runCatching { dexClasses(cl) }.getOrElse {
            xlog("SendProbe: 枚举 dex 失败 $it")
            return
        }
        val targets = names.filter { it.startsWith("com.tencent.mm.pluginsdk.ui.chat") }
        xlog("SendProbe: pluginsdk.ui.chat 下 ${targets.size} 个类")

        var shown = 0
        for (name in targets) {
            if (shown >= 30) break
            val c = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull() ?: continue
            for (m in c.declaredMethods) {
                if (shown >= 30) break
                val p = m.parameterTypes
                val strings = p.count { it == String::class.java }
                if (strings < 2) continue
                shown++
                xlog("SendProbe 方法: ${c.name}#${m.name}(${p.joinToString { it.simpleName }}) static=${Modifier.isStatic(m.modifiers)}")
            }
        }
    }

    /**
     * 从包扫描里挑出来的发送候选（都带 2 个 String，形状最像 talker + content）：
     *   ChatFooter#F0(String, String)
     *   ChatFooter#N1(String, String)
     *   ChatFooter#y (String, String, String)
     *   va#a       (String, String)  static ← 静态方法，最适合直接调用
     *
     * 手动发一条，谁被调用、参数谁是 talker 谁是 content，一看便知。
     */
    private fun hookSendCandidates(cl: ClassLoader) {
        hookNamed("com.tencent.mm.pluginsdk.ui.chat.ChatFooter", listOf("F0", "N1", "y"), cl)
        hookNamed("com.tencent.mm.pluginsdk.ui.chat.va", listOf("a"), cl)
    }

    private fun hookNamed(className: String, methodNames: List<String>, cl: ClassLoader) {
        runCatching {
            hookNamedOn(XposedHelpers.findClass(className, cl), methodNames)
        }.onFailure { xlog("SendProbe: hook $className 失败 ${it.message}") }
    }

    private val namedHooked = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun hookNamedOn(c: Class<*>, methodNames: List<String>) {
        val key = "${c.name}@${System.identityHashCode(c.classLoader)}"
        if (!namedHooked.add(key)) return
        runCatching {
            var n = 0
            for (m in c.declaredMethods) {
                if (m.name !in methodNames) continue
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (candLogsLeft <= 0) return
                            candLogsLeft--
                            val args = param.args.joinToString(" | ") { a ->
                                when (a) {
                                    null -> "null"
                                    is String -> if (a.length > 40) "\"${a.take(40)}…\"" else "\"$a\""
                                    else -> a.toString().take(30)
                                }
                            }
                            xlog("SendProbe 候选命中: ${c.name}#${m.name} = $args")
                        }
                    })
                    n++
                }
            }
            if (n > 0) xlog("SendProbe: 已 hook 候选 ${c.name} ×$n (loader=${c.classLoader?.javaClass?.simpleName})")
        }.onFailure { xlog("SendProbe: hook ${c.name} 失败 ${it.message}") }
    }

    private var candLogsLeft = 8

    /** BaseDexClassLoader.pathList.dexElements[].dexFile.entries() */
    private fun dexClasses(cl: ClassLoader): List<String> {
        val out = ArrayList<String>(8192)
        val pathList = XposedHelpers.getObjectField(cl, "pathList")
        val elements = XposedHelpers.getObjectField(pathList, "dexElements") as Array<*>
        for (el in elements) {
            if (el == null) continue
            val df = runCatching {
                XposedHelpers.getObjectField(el, "dexFile") as? dalvik.system.DexFile
            }.getOrNull() ?: continue
            val en = df.entries()
            while (en.hasMoreElements()) {
                (en.nextElement() as? String)?.let { out += it }
            }
        }
        return out
    }

    /** 把监听器对象的字段挖出来 —— 它多半持有 chat footer / context，发送 API 在那边 */
    private fun dumpListenerFields(obj: Any) {
        runCatching {
            val c = obj.javaClass
            xlog("SendProbe 监听器字段: ${c.name}")
            var n = 0
            var f = c
            while (f != null && n < 24) {
                for (field in f.declaredFields) {
                    if (n >= 24) break
                    if (Modifier.isStatic(field.modifiers)) continue
                    field.isAccessible = true
                    val v = runCatching { field.get(obj) }.getOrNull()
                    val desc = when (v) {
                        null -> "null"
                        is String -> "\"${v.take(30)}\""
                        else -> v.javaClass.name
                    }
                    xlog("    ${field.name}: ${field.type.simpleName} = $desc")
                    n++
                }
                f = f.superclass
            }
        }.onFailure { xlog("SendProbe: dump 字段失败 ${it.message}") }
    }

    /**
     * 发送走的协程在存储层之前就切到了线程池，insert 的栈里看不到 UI。
     * 所以从另一头抓：hook 点击事件。
     *
     * 不猜类名 —— 前 12 次点击记录类名 + id + 监听器类，
     * 并从 View.mListenerInfo 里把 OnClickListener 挖出来立刻 hook，下一次点击就能看到真正的处理逻辑。
     */
    private var clicksLeft = 12

    /** 已经 hook 过的监听器类 */
    private val hookedListeners = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun hookSendClick() {
        runCatching {
            XposedBridge.hookAllMethods(
                android.view.View::class.java,
                "performClick",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (clicksLeft <= 0) return
                        val v = param.thisObject as? android.view.View ?: return
                        clicksLeft--

                        // 点击的 OnClickListener 藏在 View.mListenerInfo.mOnClickListener，
                        // 它不在调用栈上，只能反射读出来
                        val listener = runCatching {
                            val li = XposedHelpers.getObjectField(v, "mListenerInfo")
                            XposedHelpers.getObjectField(li, "mOnClickListener")
                        }.getOrNull()

                        xlog(
                            "SendProbe 点击[$clicksLeft]: ${v.javaClass.name} id=${v.id} " +
                                "listener=${listener?.javaClass?.name ?: "null"}"
                        )
                        listener?.let { hookListener(it.javaClass) }
                    }
                }
            )
            xlog("SendProbe: 已 hook View.performClick")
        }.onFailure { xlog("SendProbe: hook 点击失败 $it") }
    }

    /** 把点击监听器的 onClick 挂上，下一次点同一个按钮就能看到真正的处理逻辑 */
    private fun hookListener(c: Class<*>) {
        if (!hookedListeners.add(c.name)) return
        runCatching {
            var n = 0
            for (m in c.declaredMethods) {
                if (m.name != "onClick" || m.parameterCount != 1) continue
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (listenerLogsLeft <= 0) return
                        listenerLogsLeft--
                        xlog("SendProbe onClick: ${c.name}#onClick")
                        Thread.currentThread().stackTrace.take(26).forEach { xlog("    at $it") }
                    }
                })
                n++
            }
            if (n > 0) xlog("SendProbe: 已 hook 监听器 $c ×$n")
        }.onFailure { xlog("SendProbe: hook 监听器失败 $it") }
    }

    private var listenerLogsLeft = 4

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
