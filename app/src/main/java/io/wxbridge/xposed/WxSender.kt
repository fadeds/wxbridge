package io.wxbridge.xposed

import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 微信的消息发送入口。文本和表情分两条，都靠「多候选 + 签名匹配」定位。
 *
 * 这是整个自动回复里**最不确定**的一环：发送接口是
 * `com.tencent.mm.model.*` 里被混淆成单字母的静态方法，版本之间类名会变。
 * 所以只在这些已知类里找，绝不全局扫描 —— 乱调一个参数相同的方法
 * 可能触发完全无关的逻辑。
 *
 * 两步初始化：
 *   init()      定位文本发送
 *   initEmoji() 定位表情发送（需要 EmojiInfo 类存在）
 */
internal object WxSender {

    private var textMethod: Method? = null
    private var textDesc: String = "未找到"

    private var emojiMethod: Method? = null
    private var emojiClass: Class<*>? = null
    private var emojiDesc: String = "未找到"

    /** 微信 8.x 里 MessageManager 的候选容器类 */
    private val CANDIDATES = listOf(
        "com.tencent.mm.model.bh",
        "com.tencent.mm.model.bb",
        "com.tencent.mm.model.ao",
        "com.tencent.mm.model.cm",
        "com.tencent.mm.model.bd",
        "com.tencent.mm.model.bg"
    )

    /** EmojiInfo 的候选类名 */
    private val EMOJI_CLASSES = listOf(
        "com.tencent.mm.storage.emotion.EmojiInfo",
        "com.tencent.mm.storage.emotion.a",
        "com.tencent.mm.storage.EmojiInfo"
    )

    fun init(cl: ClassLoader): String {
        for (name in CANDIDATES) {
            val c = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull() ?: continue
            // Method 没有 isStatic 属性，得看 modifiers
            val m = c.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) && looksLikeTextSend(it)
            } ?: continue
            runCatching { m.isAccessible = true }
            textMethod = m
            textDesc = "${c.name}#${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"
            break
        }
        if (textMethod == null) textDesc = "未找到文本发送入口"

        emojiDesc = initEmoji(cl)
        return textDesc
    }

    private fun initEmoji(cl: ClassLoader): String {
        val emojiCls = EMOJI_CLASSES.firstNotNullOfOrNull {
            runCatching { XposedHelpers.findClass(it, cl) }.getOrNull()
        } ?: return "未找到 EmojiInfo 类"

        for (name in CANDIDATES) {
            val c = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull() ?: continue
            val m = c.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.parameterCount in 2..4 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1].name == emojiCls.name
            } ?: continue
            runCatching { m.isAccessible = true }
            emojiMethod = m
            emojiClass = emojiCls
            return "${c.name}#${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"
        }
        return "未找到表情发送入口"
    }

    fun describe(): String = "文本=$textDesc | 表情=$emojiDesc"

    fun textAvailable(): Boolean = textMethod != null

    fun emojiAvailable(): Boolean = emojiMethod != null && emojiClass != null

    private fun looksLikeTextSend(m: Method): Boolean {
        val p = m.parameterTypes
        if (p.size < 3 || p.size > 6) return false
        if (p[0] != String::class.java) return false          // talker
        if (p[1] != String::class.java) return false          // content
        if (p[2] != Int::class.javaPrimitiveType) return false // type
        return true
    }

    fun sendText(talker: String, text: String): Boolean {
        val m = textMethod ?: return false
        return runCatching {
            val args = arrayOfNulls<Any?>(m.parameterCount)
            args[0] = talker
            args[1] = text
            args[2] = 1 // 1 = 文本消息
            for (i in 3 until m.parameterCount) {
                args[i] = if (m.parameterTypes[i].isPrimitive) 0 else null
            }
            m.invoke(null, *args)
            true
        }.onFailure { xlog("sendText failed: $it") }.getOrDefault(false)
    }

    /**
     * 发表情包。只需要 md5 和文件大小 —— 表情本体微信自己会从本地缓存或 CDN 取，
     * 不用把文件读出来。
     */
    fun sendEmoji(talker: String, md5: String, size: Int): Boolean {
        val m = emojiMethod ?: return false
        val cls = emojiClass ?: return false
        return runCatching {
            val obj = XposedHelpers.newInstance(cls)
            runCatching { XposedHelpers.callMethod(obj, "setMd5", md5) }
            runCatching { XposedHelpers.callMethod(obj, "setSize", size) }
            runCatching { XposedHelpers.callMethod(obj, "setContent", md5) }

            val args = arrayOfNulls<Any?>(m.parameterCount)
            args[0] = talker
            args[1] = obj
            for (i in 2 until m.parameterCount) {
                args[i] = if (m.parameterTypes[i].isPrimitive) 0 else null
            }
            m.invoke(null, *args)
            true
        }.onFailure { xlog("sendEmoji failed: $it") }.getOrDefault(false)
    }
}
