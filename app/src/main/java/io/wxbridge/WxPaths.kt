package io.wxbridge

import java.io.File

/** 微信本地存储的路径约定 */
object WxPaths {

    const val WX_PKG = "com.tencent.mm"
    const val WX_DATA = "/data/data/com.tencent.mm"
    const val MICRO_MSG = "$WX_DATA/MicroMsg"

    /** 主库文件名（8.x 各版本都是它） */
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
