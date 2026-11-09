package io.wxbridge.xposed

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.MethodsMatcher
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

/**
 * 用微信自己的网络层发文本消息。
 *
 * 参考 Ujhhgtg/WeKit 的做法：
 * ```
 * holder = getSendMsgObject()                  // 静态无参，拿到消息队列持有者
 * scene  = new NetSceneSendMsg(talker, text, 1, 0, null)
 * postToQueue(holder, scene)
 * ```
 *
 * 难点从来不是 API，而是**怎么找到这个被混淆的类**。
 * 之前靠类名/方法名猜，在「混淆 + Tinker 双 ClassLoader」下必然失败：
 * 试过 modelmulti（微信早不用了）、ChatFooter 的 12 个 String 方法（全不触发）、
 * va#a（是语音转文字的 diff 工具）。
 *
 * 正确做法是按 **dex 里的字符串常量**做结构匹配 —— 类名会变，日志 tag 不变：
 *   "MicroMsg.NetSceneSendMsg" / "MicroMsg.NetSceneQueue"
 */
internal object WxSendApi {

    private const val SEND_TAG = "MicroMsg.NetSceneSendMsg"
    private const val QUEUE_TAG = "MicroMsg.NetSceneQueue"
    private const val QUEUE_PKG = "com.tencent.mm.modelbase"

    private var ctor: Constructor<*>? = null
    private var postToQueue: Method? = null
    private var holderGetter: Method? = null

    @Volatile
    private var ready = false

    /** 初始化结果，直接显示在界面上 */
    @Volatile
    var status: String = "未初始化"
        private set

    /**
     * DexKit 的 so 不在微信的 nativeLibraryDir 里（我们是注入进去的模块），
     * System.loadLibrary 必然找不到。从自己的 APK 里解出来再 System.load。
     */
    private fun loadNative(ctx: Context): Boolean {
        val abi = android.os.Build.SUPPORTED_ABIS
            .firstOrNull { it.contains("arm64") } ?: android.os.Build.SUPPORTED_ABIS[0]
        val name = "libdexkit.so"
        return runCatching {
            val myApk = ctx.packageManager.getApplicationInfo("io.wxbridge", 0).sourceDir
            val dst = File(ctx.codeCacheDir, name)
            if (!dst.exists() || dst.length() == 0L) {
                ZipFile(myApk).use { zf ->
                    val entry = zf.getEntry("lib/$abi/$name")
                        ?: throw IllegalStateException("APK 里没有 lib/$abi/$name")
                    zf.getInputStream(entry).use { input ->
                        dst.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            System.load(dst.absolutePath)
            xlog("WxSendApi: 已加载 $name ($abi, ${dst.length()} 字节)")
            true
        }.onFailure { xlog("WxSendApi: 加载 $name 失败 $it") }.getOrDefault(false)
    }

    fun init(ctx: Context, cl: ClassLoader, apkPath: String) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val t0 = System.currentTimeMillis()
            var bridge: DexKitBridge? = null
            try {
                if (!loadNative(ctx)) throw IllegalStateException("native 库加载失败")
                xlog("WxSendApi: DexKit 开始解析 $apkPath")
                bridge = DexKitBridge.create(apkPath)

                // ---- 1. 发送场景类：某个方法里用到了发送 tag ----
                val sendClasses = bridge.findClass {
                    matcher(
                        ClassMatcher.create().methods(
                            MethodsMatcher.create().add(
                                MethodMatcher.create().usingStrings(SEND_TAG)
                            )
                        )
                    )
                }
                val sendNames = sendClasses.mapNotNull { runCatching { it.name }.getOrNull() }
                xlog("WxSendApi: 含发送 tag 的类 ${sendNames.size} 个：${sendNames.take(4)}")

                val sendCls = sendNames.asSequence()
                    .mapNotNull { runCatching { cl.loadClass(it) }.getOrNull() }
                    .firstOrNull { c -> c.declaredConstructors.any { looksLikeSendCtor(it) } }
                    ?: throw IllegalStateException("没找到带 (String,String,int,int,Object) 构造器的发送类")

                ctor = sendCls.declaredConstructors.first { looksLikeSendCtor(it) }
                    .apply { isAccessible = true }

                val baseName = sendCls.superclass?.name
                    ?: throw IllegalStateException("发送类没有父类")
                xlog("WxSendApi: 发送类=${sendCls.name} 父类=${baseName.substringAfterLast('.')}")

                // ---- 2. 队列类：modelbase 包里用到队列 tag 的类 ----
                val queueClasses = bridge.findClass {
                    searchPackages(QUEUE_PKG)
                    matcher(
                        ClassMatcher.create().methods(
                            MethodsMatcher.create().add(
                                MethodMatcher.create().usingStrings(QUEUE_TAG)
                            )
                        )
                    )
                }
                val queueNames = queueClasses.mapNotNull { runCatching { it.name }.getOrNull() }
                xlog("WxSendApi: 队列候选 ${queueNames.size} 个：${queueNames.take(4)}")

                for (name in queueNames) {
                    val c = runCatching { cl.loadClass(name) }.getOrNull() ?: continue
                    // 入队方法：参数是 NetSceneBase、返回 boolean
                    val post = c.declaredMethods.firstOrNull {
                        it.parameterCount == 1 &&
                            it.parameterTypes[0].name == baseName &&
                            it.returnType == java.lang.Boolean.TYPE
                    } ?: continue
                    post.isAccessible = true
                    postToQueue = post

                    // 持有者：静态无参、返回队列类自身
                    holderGetter = c.declaredMethods.firstOrNull {
                        Modifier.isStatic(it.modifiers) &&
                            it.parameterCount == 0 &&
                            it.returnType == c
                    }?.apply { isAccessible = true }

                    if (Modifier.isStatic(post.modifiers) || holderGetter != null) {
                        xlog(
                            "WxSendApi: 队列=${c.name} 入队=${post.name} " +
                                "holder=${holderGetter?.name ?: "静态调用"}"
                        )
                        break
                    }
                    // 这个类不合适，继续找下一个
                    postToQueue = null
                }

                if (postToQueue == null) throw IllegalStateException("没找到入队方法 (NetSceneBase)->boolean")
                if (!Modifier.isStatic(postToQueue!!.modifiers) && holderGetter == null) {
                    throw IllegalStateException("入队方法是实例方法，但找不到持有者")
                }

                ready = true
                status = "就绪（${System.currentTimeMillis() - t0}ms）"
                xlog("WxSendApi: $status")
            } catch (t: Throwable) {
                status = "初始化失败：${t.javaClass.simpleName}: ${t.message}"
                xlog("WxSendApi: $status")
            } finally {
                runCatching { bridge?.close() }
            }
        }
    }

    private fun looksLikeSendCtor(c: Constructor<*>): Boolean {
        val p = c.parameterTypes
        if (p.size != 5 && p.size != 6) return false
        if (p[0] != String::class.java || p[1] != String::class.java) return false
        if (p[2] != Integer.TYPE || p[3] != Integer.TYPE) return false
        return true
    }

    /** @return null 表示已发起，非 null 是失败原因 */
    fun send(talker: String, text: String): String? {
        if (!ready) return "发送模块未就绪：$status"
        val c = ctor ?: return "构造器未就绪"
        val post = postToQueue ?: return "入队方法未就绪"

        return try {
            // 六参版本显式传 msgsource，空串保留微信自己生成的
            val scene = if (c.parameterTypes.size == 6) {
                c.newInstance(talker, text, 1, 0, null, "")
            } else {
                c.newInstance(talker, text, 1, 0, null)
            }

            val ok = if (Modifier.isStatic(post.modifiers)) {
                post.invoke(null, scene)
            } else {
                val owner = holderGetter?.invoke(null)
                    ?: return "拿不到消息队列持有者"
                post.invoke(owner, scene)
            }
            if (ok == false) "微信拒绝入队（返回 false）" else null
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            "${cause.javaClass.simpleName}: ${cause.message}"
        }
    }
}
