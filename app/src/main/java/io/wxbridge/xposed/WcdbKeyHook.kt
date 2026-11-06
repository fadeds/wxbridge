package io.wxbridge.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 从 WCDB 的开库路径里截获 (path, password)。
 *
 * 微信 8.0.79 实测踩到的坑：只在 `lpparam.classLoader` 上 hook 是不够的。
 * 微信启用了 Tinker 热修复，实际运行的类由 `DelegateLastClassLoader` 从
 * `/data/user/0/com.tencent.mm/tinker/patch-xxx/dex/` 加载 —— 同一个类名在
 * 两个 ClassLoader 里是两个不同的 Class 对象，hook 了主 ClassLoader 那份，
 * 真实调用走的是另一份，所以一次都不触发。
 *
 * 解法：挂 `ClassLoader.loadClass`，等目标类真正被加载时再 hook。
 */
internal object WcdbKeyHook {

    /**
     * 主库实例（com.tencent.wcdb.database.SQLiteDatabase）。
     * 自动回复在微信进程内直接查它 —— 同一进程、已是解密状态。
     */
    @Volatile
    var mainDb: Any? = null
        private set

    /** 主库绝对路径，用来推 MicroMsg/<md5>/ 目录（表情包所在位置） */
    @Volatile
    var mainDbPath: String? = null
        private set

    /** 类名 → 需要 hook 的方法名 */
    private val TARGETS: Map<String, List<String>> = mapOf(
        "com.tencent.wcdb.database.SQLiteDatabase" to
            listOf("openDatabase", "openOrCreateDatabase", "open"),
        "com.tencent.wcdb.database.SQLiteDatabaseConfiguration" to
            listOf("<init>"),
        "com.tencent.wcdb.database.SQLiteConnection" to
            listOf("open")
    )

    /** 已经 hook 过的 (类名 + ClassLoader 身份)，避免重复挂 */
    private val hooked = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 参数类型诊断只打前几次，避免刷屏 */
    private var debugLeft = 6

    fun install(cl: ClassLoader) {
        // 1. 拦截类加载，覆盖 Tinker 的 DelegateLastClassLoader
        runCatching {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val c = param.result as? Class<*> ?: return
                    if (TARGETS.containsKey(c.name)) hookClass(c)
                }
            })
        }.onFailure { xlog("hook ClassLoader.loadClass failed: $it") }

        // 2. 主 ClassLoader 里已经加载过的，立刻 hook 上
        for (name in TARGETS.keys) {
            runCatching { XposedHelpers.findClass(name, cl) }
                .getOrNull()
                ?.let { hookClass(it) }
        }

        if (hooked.isEmpty()) {
            xlog("WARNING: WCDB hook 一个都没装上 —— 微信可能换了数据库实现")
        }
    }

    private fun hookClass(c: Class<*>) {
        val key = "${c.name}@${System.identityHashCode(c.classLoader)}"
        if (!hooked.add(key)) return

        val methods = TARGETS[c.name] ?: return
        var ok = 0
        for (m in methods) {
            runCatching {
                XposedBridge.hookAllMethods(c, m, callback)
                ok++
            }.onFailure { xlog("hook ${c.name}#$m failed: $it") }
        }
        if (ok > 0) {
            xlog("hooked ${c.name} ×$ok  (loader=${c.classLoader?.javaClass?.simpleName})")
        }
    }

    private val callback = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val args = param.args ?: return
            var path: String? = null
            var pwd: ByteArray? = null

            for (a in args) {
                when (a) {
                    is String -> if (looksLikeDbPath(a)) path = a
                    // 密码是 byte[]，长度很短；用它排除掉其他 byte[] 参数（如 BLOB）
                    is ByteArray -> if (a.isNotEmpty() && a.size <= 64) pwd = a
                    // 万一某个版本把密码换成 char[]，也接住
                    is CharArray -> if (a.isNotEmpty() && a.size <= 64) {
                        pwd = String(a).toByteArray(Charsets.UTF_8)
                    }
                }
            }

            // 诊断：有路径但没密码，说明密码不在 byte[]/char[] 里，
            // 把参数类型打出来，下次照着改就行
            if (path != null && pwd == null && debugLeft > 0) {
                debugLeft--
                xlog("args without pwd: ${args.joinToString { it?.javaClass?.simpleName ?: "null" }}")
            }

            val p = path ?: return

            // 主库实例留给自动回复用
            if (p.endsWith("EnMicroMsg.db")) {
                param.result?.let {
                    mainDb = it
                    mainDbPath = p
                }
            }

            val k = pwd ?: return
            runCatching { KeyBridge.onKey(p, k) }
        }
    }

    private fun looksLikeDbPath(s: String): Boolean =
        s.endsWith(".db") && (s.startsWith("/") || s.contains("MicroMsg"))
}
