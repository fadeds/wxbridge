package io.wxbridge.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier

/**
 * 发送入口探测。
 *
 * 背景：手动发消息时从 insert 往上抓到的是**存储层**（ii5.b0 / storage.f9），
 * 不是发送 API 本身。所以这里两条腿一起走：
 *
 * 1) 扫 dex，把 `com.tencent.mm.modelmulti` 下所有「静态 + (String, String, int…)」
 *    的方法名列出来 —— 微信历代的文本发送入口都在这个包里，形状也稳定。
 * 2) 栈里出现了 `ox0.j0#r`（存储之上那一层），把它所有重载都 hook 一遍，
 *    用户手动发一条，就能看到真实的参数形状和值。
 *
 * 这是纯诊断，不改任何行为：只读、只打日志。
 */
internal object SendProbe {

    private const val MAX_LOG = 24

    fun install(cl: ClassLoader) {
        scanModelMulti(cl)
        hookSuspect(cl)
    }

    // ---------------- 1) dex 扫描 ----------------

    private fun scanModelMulti(cl: ClassLoader) {
        val names = runCatching { dexClasses(cl) }.getOrElse {
            xlog("SendProbe: 枚举 dex 失败 $it")
            return
        }
        xlog("SendProbe: dex 共 ${names.size} 个类")

        val targets = names.filter {
            it.startsWith("com.tencent.mm.modelmulti") || it.startsWith("com.tencent.mm.model.")
        }
        xlog("SendProbe: model/multi 候选类 ${targets.size} 个")

        var logged = 0
        for (name in targets) {
            if (logged >= MAX_LOG) break
            val c = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull() ?: continue
            for (m in c.declaredMethods) {
                if (!Modifier.isStatic(m.modifiers)) continue
                val p = m.parameterTypes
                if (p.size < 3 || p.size > 6) continue
                if (p[0] != String::class.java || p[1] != String::class.java) continue
                if (p[2] != Int::class.javaPrimitiveType) continue
                if (logged >= MAX_LOG) break
                logged++
                xlog("SendProbe 候选: ${c.name}#${m.name}(${p.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}")
            }
        }
        if (logged == 0) xlog("SendProbe: modelmulti 里没找到 (String,String,int) 形状的静态方法")
    }

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

    // ---------------- 2) hook 栈里出现的那一层 ----------------

    private val SUSPECT = listOf("ox0.j0", "yh0.p")

    private fun hookSuspect(cl: ClassLoader) {
        for (name in SUSPECT) {
            runCatching {
                val c = XposedHelpers.findClass(name, cl)
                var n = 0
                for (m in c.declaredMethods) {
                    // 只 hook 少数几个，避免把微信主流程拖慢
                    if (n >= 6) break
                    runCatching {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                xlog(
                                    "SendProbe 命中: ${c.name}#${m.name}" +
                                        "(${m.parameterTypes.joinToString { it.simpleName }})" +
                                        " args=${param.args.joinToString { a -> a?.toString()?.take(40) ?: "null" }}"
                                )
                            }
                        })
                        n++
                    }
                }
                if (n > 0) xlog("SendProbe: 已 hook $name ×$n")
            }.onFailure { xlog("SendProbe: hook $name 失败 ${it.message}") }
        }
    }
}
