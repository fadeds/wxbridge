package io.wxbridge.xposed

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

internal const val WX_PKG = "com.tencent.mm"
internal const val TAG = "WxBridge"

internal fun xlog(msg: String) {
    runCatching { XposedBridge.log("[$TAG] $msg") }
}

/**
 * 唯一入口。LSPosed 依据 assets/xposed_init 加载本类。
 *
 * 设计原则：hook 层只负责「把 SQLCipher 密码捞出来」。
 * 解密、解析、AI 全部在主 App 进程里用 root 完成，
 * 这样微信升级时只需要修 WcdbKeyHook 一个文件。
 */
class XposedEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != WX_PKG) return

        xlog("loaded in process=${lpparam.processName}")

        // 微信主进程才碰聊天库；push/appbrand 等子进程直接跳过，
        // 减少被检测面，也避免在子进程里做无用的 hook。
        if (lpparam.processName == WX_PKG) {
            hookAppContext()
            val cl = lpparam.classLoader
            if (cl == null) {
                xlog("classLoader is null, abort")
            } else {
                DataBridge.install(cl)
                WcdbKeyHook.install(cl)
                // 自动回复引擎也跑在微信进程：能直接用内存里的库实例和发送 API
                AutoReplyEngine.start(cl)
                // 发送入口是混淆的，先探测出候选，别靠猜类名
                SendProbe.install(cl)
                // 发送走「驱动微信自己的界面」，不依赖混淆的发送 API
                WxUiSender.install(cl)
            }
        }
    }

    /**
     * 拿 Context 用来写 shared_prefs。
     * LSPosed 在 Application.attach 之前就加载模块，所以这里一定赶得上；
     * 万一错过了，KeyBridge 内部还有 AndroidAppHelper 兜底。
     */
    private fun hookAppContext() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.args.getOrNull(0) as? Context)?.let {
                            KeyBridge.attach(it)
                            // 数据桥也用这个 Context 注册广播监听
                            DataBridge.attach(it)
                        }
                    }
                }
            )
        }.onFailure { xlog("hook Application.attach failed: $it") }
    }
}
