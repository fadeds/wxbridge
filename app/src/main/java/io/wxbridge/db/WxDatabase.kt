package io.wxbridge.db

import android.util.Log
import io.wxbridge.RootShell
import io.wxbridge.WxKeyReader
import io.wxbridge.WxPaths
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * 管理「把微信加密库复制到 app 私有目录 → 用 SQLCipher 打开」这件事。
 *
 * 为什么不在微信进程里直接查？
 * 因为查询逻辑一旦跑在微信进程，任何一次崩溃都会连累微信本身，
 * 而且调试极其痛苦。复制 + 本地解密虽然多花几百毫秒，但完全隔离、随便崩。
 */
class WxDatabase(
    private val appFilesDir: File,
    private val key: WxKeyReader.WxKey
) {
    private val workDir: File = WxPaths.workDir(appFilesDir)

    @Volatile
    private var main: SQLiteDatabase? = null

    @Volatile
    private var openedSpec: DbOpener.CipherSpec? = null

    val specLabel: String get() = openedSpec?.label ?: "未打开"

    /**
     * 重新复制 + 打开。每次同步前调用。
     * @return 人类可读的诊断信息，直接打到 UI 上
     */
    fun refresh(): String {
        close()
        val log = StringBuilder()

        val dirFiles = RootShell.listDir(key.dbDir)
        log.append("库目录: ${key.dbDir}\n")
        log.append("目录内文件: ${dirFiles.size} 个\n")
        if (dirFiles.isEmpty()) {
            return log.append("！读不到库目录，root 或路径有问题").toString()
        }
        if (key.dbName !in dirFiles) {
            log.append("！目录里没有 ${key.dbName}\n")
            log.append("  实际看到: ${dirFiles.take(20).joinToString(", ")}\n")
            return log.toString()
        }

        val localMain = copyWithWal(key.dbDir, key.dbName)
            ?: return log.append("！复制主库失败").toString()
        log.append("主库副本: ${localMain.length() / 1024}KB\n")

        return try {
            val (db, spec) = DbOpener.open(localMain, key.password)
            main = db
            openedSpec = spec
            log.append("解密成功，参数: ${spec.label}\n")
            log.append("表数量: ${MainRepo(db).tableNames().size}\n")
            log.toString()
        } catch (e: DbOpener.OpenFailure) {
            log.append("！解密失败\n")
            e.tried.forEach { log.append("  - $it\n") }
            log.append("→ 大概率是密码不对：先冷启动一次微信让 hook 重新抓 key\n")
            log.toString()
        } catch (t: Throwable) {
            Log.e(TAG, "refresh failed", t)
            log.append("！打开异常: ${t.javaClass.simpleName} ${t.message}\n")
            log.toString()
        }
    }

    fun repo(): MainRepo? = main?.let { MainRepo(it) }

    /**
     * 打开附属库（rcontact.db / imageinfo2.db 等）。
     * 微信 8.0.79 每个库的密码不同，必须按库名取对应密码，不能拿主库的凑。
     */
    fun openExtra(name: String): SQLiteDatabase? {
        val src = "${key.dbDir}/$name"
        val local = copyWithWal(key.dbDir, name) ?: return null
        val pwd = key.passwordFor(src)
        return runCatching { DbOpener.open(local, pwd).first }.getOrNull()
    }

    /** 单个库的扫描结果 */
    data class DbScan(
        val file: String,
        val opened: Boolean,
        val tables: List<String>,
        val error: String?
    )

    /**
     * 遍历库目录下所有 .db，列出表名。
     *
     * 用途：微信支持「搜索聊天图片里的文字」，说明它可能已经把 OCR 结果写进本地索引了。
     * 如果真有 FTS/OCR 相关表，图片文字就可以直接查库，既不用跑 OCR 也不用调视觉模型。
     * 先探测，再决定要不要写查询 —— 别凭猜测写死表名。
     */
    fun scanAllDbs(limit: Int = 12): List<DbScan> {
        val names = RootShell.listDir(key.dbDir)
            .filter { it.endsWith(".db") }
            .sortedBy { if (it == key.dbName) 0 else 1 }
            .take(limit)

        val out = ArrayList<DbScan>(names.size)
        for (name in names) {
            val local = copyWithWal(key.dbDir, name)
            if (local == null) {
                out += DbScan(name, false, emptyList(), "复制失败")
                continue
            }
            try {
                val (db, _) = DbOpener.open(local, key.passwordFor("${key.dbDir}/$name"))
                val tables = runCatching { MainRepo(db).tableNames() }.getOrDefault(emptyList())
                runCatching { db.close() }
                out += DbScan(name, true, tables.sorted(), null)
            } catch (t: Throwable) {
                out += DbScan(name, false, emptyList(), t.message?.take(80))
            }
        }
        return out
    }

    fun close() {
        runCatching { main?.close() }
        main = null
    }

    private fun copyWithWal(dir: String, name: String): File? {
        val src = "$dir/$name"
        val dst = File(workDir, name)

        // 本地残留的 -shm 是上一台设备/上一次会话的，带着它打开会导致 WAL 校验失败
        File(workDir, "$name-shm").delete()
        File(workDir, "$name-wal").delete()

        if (!RootShell.copyToAppData(src, dst, 180)) return null

        // WAL 里可能还有没落盘的最新消息，必须一起拿走，否则可能少最后几条
        val wal = File(workDir, "$name-wal")
        if (!RootShell.copyToAppData("$src-wal", wal, 60)) {
            wal.delete()
        }
        return dst
    }

    private companion object {
        const val TAG = "WxBridgeDatabase"
    }
}
