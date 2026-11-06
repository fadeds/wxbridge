package io.wxbridge.db

import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import java.io.File

/**
 * 用 SQLCipher 打开微信的加密库。
 *
 * 参数不确定性是这个项目最大的工程风险：微信的 WCDB 早期用 SQLCipher 3 参数
 * （pageSize=1024 / kdfIter=64000 / SHA1），8.0.4x 之后部分版本迁到 SQLCipher 4
 * （4096 / 256000 / SHA512）。所以这里做参数探测：三组候选逐个试，
 * 能跑通 `SELECT count(*) FROM sqlite_master` 的那组即为正确参数。
 *
 * 一旦探测成功就把组合缓存起来，后续打开直接命中。
 */
object DbOpener {

    private const val TAG = "WxBridgeDb"

    init {
        runCatching { System.loadLibrary("sqlcipher") }
            .onFailure { Log.e(TAG, "load sqlcipher failed", it) }
    }

    data class CipherSpec(
        val pageSize: Int,
        val kdfIter: Int,
        val hmac: String,
        val kdf: String,
        val label: String
    )

    val SPECS: List<CipherSpec> = listOf(
        CipherSpec(1024, 64000, "HMAC_SHA1", "PBKDF2_HMAC_SHA1", "SQLCipher3 / WCDB 默认"),
        CipherSpec(4096, 256000, "HMAC_SHA512", "PBKDF2_HMAC_SHA512", "SQLCipher4 默认"),
        CipherSpec(4096, 64000, "HMAC_SHA512", "PBKDF2_HMAC_SHA512", "SQLCipher4 / kdf64k")
    )

    /** 命中过的参数组合，下次直接用它，省掉两轮失败尝试 */
    @Volatile
    private var hitSpec: CipherSpec? = null

    class OpenFailure(message: String, val tried: List<String>) : Exception(message)

    fun open(file: File, password: ByteArray): Pair<SQLiteDatabase, CipherSpec> {
        val candidates = buildList {
            hitSpec?.let { add(it) }
            SPECS.filter { it != hitSpec }.forEach { add(it) }
        }
        val tried = mutableListOf<String>()

        for (spec in candidates) {
            try {
                val db = openWith(file, password, spec)
                // 真正解密成功的判定：能读到 schema
                val n = countTables(db)
                if (n > 0) {
                    hitSpec = spec
                    Log.i(TAG, "opened ${file.name} with ${spec.label}, tables=$n")
                    return db to spec
                }
                db.close()
                tried += "${spec.label}: 能打开但无表"
            } catch (t: Throwable) {
                tried += "${spec.label}: ${t.javaClass.simpleName} ${t.message?.take(80) ?: ""}"
                Log.w(TAG, "open fail with ${spec.label}", t)
            }
        }
        throw OpenFailure("所有密码参数组合都失败，密码可能不匹配", tried)
    }

    private fun openWith(file: File, password: ByteArray, spec: CipherSpec): SQLiteDatabase {
        val hook = object : SQLiteDatabaseHook {
            override fun preKey(connection: SQLiteConnection) {
                // page_size 必须在 key 之前设置
                execPragma(connection, "PRAGMA cipher_page_size = ${spec.pageSize};")
                execPragma(connection, "PRAGMA kdf_iter = ${spec.kdfIter};")
                execPragma(connection, "PRAGMA cipher_hmac_algorithm = ${spec.hmac};")
                execPragma(connection, "PRAGMA cipher_kdf_algorithm = ${spec.kdf};")
            }

            override fun postKey(connection: SQLiteConnection) {}
        }

        // openDatabase 的重载签名在各版本间有出入（4 参 / 5 参 / 6 参），
        // 用反射按「String + byte[] + … + SQLiteDatabaseHook」定位最稳。
        val cls = SQLiteDatabase::class.java
        val method = cls.methods
            .filter { it.name == "openDatabase" }
            .filter {
                it.parameterTypes.size >= 4 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == ByteArray::class.java
            }
            .sortedByDescending { it.parameterTypes.size }   // 带 hook 的优先
            .firstOrNull { it.parameterTypes.any { p -> p == SQLiteDatabaseHook::class.java } }
            ?: throw IllegalStateException("找不到带 SQLiteDatabaseHook 的 openDatabase 重载")

        val args = arrayOfNulls<Any?>(method.parameterTypes.size)
        args[0] = file.absolutePath
        args[1] = password
        for (i in 2 until method.parameterTypes.size) {
            val p = method.parameterTypes[i]
            args[i] = when {
                p == SQLiteDatabaseHook::class.java -> hook
                // OPEN_READONLY = 0x1，写字面量避免依赖该常量在各版本里的可见性
                p.isPrimitive && p == Int::class.javaPrimitiveType -> 1
                p.isPrimitive -> 0
                p.isArray -> java.lang.reflect.Array.newInstance(p.componentType, 0)
                else -> null
            }
        }

        return method.invoke(null, *args) as SQLiteDatabase
    }

    /**
     * SQLiteConnection.execute 的签名同样不稳定（2 参 / 3 参），
     * 反射找出参数最少的那个，非 String 参数一律填安全默认值。
     */
    private fun execPragma(conn: SQLiteConnection, sql: String) {
        val m = conn.javaClass.methods
            .filter { it.name == "execute" && it.parameterTypes.firstOrNull() == String::class.java }
            .minByOrNull { it.parameterCount }
            ?: return
        val args = arrayOfNulls<Any?>(m.parameterCount)
        args[0] = sql
        for (i in 1 until m.parameterCount) {
            val p = m.parameterTypes[i]
            args[i] = when {
                p.isArray -> java.lang.reflect.Array.newInstance(p.componentType, 0)
                p.isPrimitive -> 0
                else -> null
            }
        }
        runCatching { m.invoke(conn, *args) }
    }

    private fun countTables(db: SQLiteDatabase): Int {
        var cursor: android.database.Cursor? = null
        return try {
            cursor = db.rawQuery("SELECT count(*) FROM sqlite_master", null)
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } catch (t: Throwable) {
            0
        } finally {
            runCatching { cursor?.close() }
        }
    }
}
