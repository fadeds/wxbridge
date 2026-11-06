package io.wxbridge.db

import android.util.Log
import io.wxbridge.RootShell
import java.io.File

/**
 * 在 App 进程里自己找出「消息主库」。
 *
 * 为什么不能只靠 hook？
 * hook 只能在微信**自己打开某个库**的时候顺手看一眼那个库有没有 message 表。
 * 主库是懒加载的：微信停在会话列表时根本不开它，于是永远探测不到。
 * 而 App 侧有 root、又已经拿到了若干把 key，完全可以主动把库解开来看。
 *
 * 判定标准只有一条：库里有没有 `message` 表。不靠文件名猜 ——
 * 微信 8.0.79 根本没有 EnMicroMsg.db，猜名字这条路已经被证伪。
 */
object MainDbFinder {

    private const val TAG = "WxBridgeMainDb"

    /** 候选 key 最多试几把 */
    private const val MAX_KEYS = 3

    /**
     * @param key 解开主库用的那把 key。主库如果是被「猜中的公共 key」打开的，
     *            调用方必须把它记进 keys，否则后续 openExtra 又会找不到密码。
     */
    data class Hit(val path: String, val tableCount: Int, val key: ByteArray)

    /**
     * @param dbDir   库目录（绝对路径）
     * @param keys    hook 已捕获的 库路径 → 密码
     * @param workDir 解密副本目录，调用方保证已存在
     * @param limit   最多试几个库文件
     */
    fun find(
        dbDir: String,
        keys: Map<String, ByteArray>,
        workDir: File,
        limit: Int = 8,
        onLog: (String) -> Unit = {}
    ): Hit? {
        val files = RootShell.listDbFiles(dbDir)
        if (files.isEmpty()) {
            onLog("本地探测：读不到 $dbDir 下的 .db（root 或路径有问题）")
            return null
        }

        // 候选 key：按「被多少个库共用」排序 —— 共用的多半是微信的默认 key。
        // 主库自己的 key 只有微信开过它才抓得到，所以不能只押这一把。
        val commonKeys = keys.values
            .groupBy { it.joinToString(",") { b -> (b.toInt() and 0xFF).toString(16) } }
            .entries
            .sortedByDescending { it.value.size }
            .map { it.value.first() }
            .take(MAX_KEYS)

        onLog("本地探测：${files.size} 个库文件，候选 key ${commonKeys.size} 把（按共用次数排序）")

        files.take(limit).forEachIndexed { idx, entry ->
            val name = entry.first
            val size = entry.second
            val path = "$dbDir/$name"
            val tag = "[${idx + 1}/${minOf(files.size, limit)}] $name (${size / 1024}KB)"

            if (size < 4096) {
                onLog("  $tag 太小，跳过")
                return@forEachIndexed
            }

            // 这个库自己有 key 就先用它，再退到公共候选
            val tryKeys = buildList {
                keys[path]?.let { k -> add(k) }
                commonKeys.forEach { k -> if (none { it.contentEquals(k) }) add(k) }
            }.take(MAX_KEYS)

            val dst = File(workDir, name)
            File(workDir, "$name-shm").delete()
            File(workDir, "$name-wal").delete()

            // 主库可能有几百 MB，30 秒的默认超时不够，按大小放宽
            val copyTimeout = 30L + size / 5_000_000L
            if (!RootShell.copyToAppData(path, dst, copyTimeout)) {
                onLog("  $tag 复制失败或超时，跳过")
                return@forEachIndexed
            }
            RootShell.copyToAppData("$path-wal", File(workDir, "$name-wal"), 60)

            for ((ki, key) in tryKeys.withIndex()) {
                val db = runCatching { DbOpener.open(dst, key).first }.getOrNull()
                if (db == null) {
                    onLog("  $tag key#${ki + 1} 解不开")
                    continue
                }
                val tableCount = countTables(db)
                val hasMsg = hasMessage(db)
                runCatching { db.close() }

                if (hasMsg) {
                    onLog("  $tag key#${ki + 1} 含 message 表 → 主库（$tableCount 张表）")
                    Log.i(TAG, "main db = $path")
                    return Hit(path, tableCount, key)
                }
                // 这个 key 能解开，说明 key 是对的，只是库选错了 —— 换下一个文件
                onLog("  $tag 解开但无 message 表（$tableCount 张表），跳过")
                break
            }

            cleanUp(workDir, name)
        }

        return null
    }

    private fun countTables(db: net.zetetic.database.sqlcipher.SQLiteDatabase): Int =
        runCatching {
            db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table'", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        }.getOrDefault(0)

    private fun hasMessage(db: net.zetetic.database.sqlcipher.SQLiteDatabase): Boolean =
        runCatching {
            db.rawQuery(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='message'",
                null
            ).use { if (it.moveToFirst()) it.getInt(0) > 0 else false }
        }.getOrDefault(false)

    private fun cleanUp(workDir: File, name: String) {
        runCatching { File(workDir, name).delete() }
        runCatching { File(workDir, "$name-wal").delete() }
        runCatching { File(workDir, "$name-shm").delete() }
    }
}
