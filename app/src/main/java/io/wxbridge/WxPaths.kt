package io.wxbridge

import java.io.File

/** 微信本地存储的路径约定 */
object WxPaths {

    const val WX_PKG = "com.tencent.mm"
    const val WX_DATA = "/data/data/com.tencent.mm"

    /** KernelSU 的 su 在全局 namespace 里能看到的那个写法 */
    const val WX_DATA_USER = "/data/user/0/com.tencent.mm"
    const val MICRO_MSG = "$WX_DATA/MicroMsg"

    /**
     * 微信数据目录的候选路径，按命中概率排序。
     *
     * 8.0.79 实测：App 通过 su 跑的 shell 处在 KernelSU 的全局 mount namespace 里，
     * 那里 `/data/data/com.tencent.mm` 报的是 **No such file or directory**（不是权限拒绝），
     * 而 `/data/user/0/com.tencent.mm` 才真实存在。两者在普通进程里是同一个目录，
     * 只是命名空间不同 —— 所以这里不能写死一个。
     */
    fun wxDataDirs(): List<String> = listOf(
        "/data/user/0/$WX_PKG",
        "/data/data/$WX_PKG",
        "/data/user_de/0/$WX_PKG"
    )

    fun microMsgDirs(): List<String> = wxDataDirs().map { "$it/MicroMsg" }

    fun prefsFiles(): List<String> = wxDataDirs().map { "$it/shared_prefs/wxbridge.xml" }

    /**
     * 旧版（8.0.7x 及更早）的主库文件名。
     *
     * 8.0.79 实测已经没有这个文件，主库改由 hook 在运行时探测
     * （见 WcdbKeyHook.hasMessageTable）。这里保留仅供兼容判断，
     * 不要再用它去拼路径。
     */
    const val MAIN_DB = "EnMicroMsg.db"

    /** App 侧工作目录，用来放脱密后的库副本 */
    fun workDir(appFilesDir: File): File =
        File(appFilesDir, "wxdb").apply { mkdirs() }

    fun dbDirOf(dbPath: String): String =
        File(dbPath).parent ?: "$MICRO_MSG/unknown"

    /** 微信 8.x 后期把表拆成多个库，都放在 MicroMsg/<32位md5>/ 下 */
    fun possibleDbFiles(): List<String> = listOf(
        "EnMicroMsg.db",
        "rcontact.db",
        "imageinfo2.db",
        "voiceinfo.db",
        "emoticon.db",
        "biz.db",
        "newmsg.db"
    )

    fun imageRoot(dbDir: String) = "$dbDir/image2"
    fun voiceRoot(dbDir: String) = "$dbDir/voice2"
    fun videoRoot(dbDir: String) = "$dbDir/video"
}
