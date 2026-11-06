package io.wxbridge

/**
 * 上云前的脱敏。
 *
 * 原则：LLM 只需要「谁说了什么」，不需要知道对方到底是谁。
 * 所以把所有身份标识换成稳定代号（U1/U2…），敏感数字打码，
 * 同一次请求内映射保持一致 —— 这样「U1 和 U2 讨论了预算」仍然可读。
 */
object Redactor {

    private val WXID = Regex("""wxid_[A-Za-z0-9_\-]{3,}""")
    private val PHONE = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    private val ID_CARD = Regex("""(?<!\d)\d{17}[\dXx](?!\d)""")
    private val BANK_CARD = Regex("""(?<!\d)\d{16,19}(?!\d)""")
    private val EMAIL = Regex("""([A-Za-z0-9._%+\-])[A-Za-z0-9._%+\-]*@([A-Za-z0-9.\-]+\.[A-Za-z]{2,})""")

    class Session {
        private val map = LinkedHashMap<String, String>()

        fun alias(id: String): String = map.getOrPut(id) { "U${map.size + 1}" }

        fun redact(text: String, displayName: String? = null): String {
            var s = text

            // 1. 已知微信号 → 代号
            WXID.findAll(s).map { it.value }.toSet().forEach { s = s.replace(it, alias(it)) }
            Regex("""[A-Za-z0-9_\-]{6,}@chatroom""").findAll(s).map { it.value }.toSet()
                .forEach { s = s.replace(it, "群聊${alias(it)}") }

            // 2. 备注名 / 昵称 → 代号（长的先换，避免短名是长名的子串）
            if (displayName != null && displayName.length >= 2) {
                s = s.replace(displayName, alias(displayName))
            }

            // 3. 数字类敏感信息打码
            s = ID_CARD.replace(s) { it.value.take(4) + "**********" + it.value.takeLast(2) }
            s = BANK_CARD.replace(s) { it.value.take(4) + " **** **** " + it.value.takeLast(4) }
            s = PHONE.replace(s) { it.value.take(3) + "****" + it.value.takeLast(4) }
            s = EMAIL.replace(s) { "${it.groupValues[1]}***@${it.groupValues[2]}" }

            return s
        }
    }

    /** 单次批处理用 */
    fun newSession() = Session()
}
