package io.wxbridge

import android.content.Context

/**
 * 微信版本闸门。
 *
 * 项目里对版本变化的防御是「多候选 + DexKit 字符串常量匹配 + 全都 runCatching」，
 * 这保证了不会崩，但**崩不崩和回得对不对是两件事**：类结构一变，
 * 定位可能悄悄落到一个语义不同的方法上，回复内容还是发出去了。
 *
 * 所以这里额外加一层版本白名单：认得的版本才允许自动回复，
 * 不认得的默认降级为只读（同步、看数据都正常，只是不写），
 * 用户可以在确认安全后手动放行。
 */
object WxVersionPolicy {

    /** 实测跑通过的微信版本。升版后先观察，确认没问题再加进来 */
    val VERIFIED = setOf("8.0.79")

    fun isVerified(v: String?): Boolean {
        val t = v?.trim() ?: return false
        return VERIFIED.contains(t)
    }

    /** 给人看的版本说明 */
    fun describe(v: String?, allowUnverified: Boolean): String = when {
        v.isNullOrBlank() -> "微信版本：未知（模块没读到，可能没在微信进程里加载）"
        isVerified(v) -> "微信版本：$v（已校验）"
        allowUnverified -> "微信版本：$v（未校验，已被你手动放行）"
        else -> "微信版本：$v（未校验 → 自动回复已暂停，只同步不写）"
    }

    /** App 侧读版本。需要 Manifest 里声明 <queries>，否则 Android 11+ 查不到 */
    fun appVersion(ctx: Context): String? = runCatching {
        ctx.packageManager.getPackageInfo(WxPaths.WX_PKG, 0).versionName
    }.getOrNull()
}
