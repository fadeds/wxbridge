package io.wxbridge

import android.util.Log
import io.wxbridge.db.ImageResolver
import io.wxbridge.db.WxMessage
import io.wxbridge.llm.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图片理解，统一走视觉模型。
 *
 * 一条通路同时解决两件事：
 *   - 读字：截图、票据、证件里的金额/单号/时间/地址
 *   - 看画：照片里是什么、什么场景
 *
 * 之前是「本地 OCR + 视觉模型」双通路，后来去掉了 OCR：
 * 原生多模态模型读字已经够用，而且省掉 ML Kit 的 15MB 体积。
 * 代价是图片必须联网识别，离线场景下图片只会是 [图片] 占位符。
 *
 * 结果按文件名缓存，同一张图只算一次；
 * 每次摘要处理图片有上限（默认 20 张），超出的直接跳过 —— 否则一次跑几百张
 * 既慢又贵，而大部分闲聊图片没有分析价值。
 */
class ImagePipeline(
    private val resolver: ImageResolver,
    cacheRoot: File,
    private val visionClient: LlmClient?,
    private val maxImages: Int = 20
) {
    private val cache = File(cacheRoot, "vlm").apply { mkdirs() }

    private var processed = 0
    private var hits = 0

    var skipped = 0
        private set

    data class Insight(val text: String, val source: String)

    val stats: String get() = "图片 $processed 张（识别 $hits，跳过 $skipped）"

    suspend fun analyze(msg: WxMessage): Insight? = withContext(Dispatchers.IO) {
        if (visionClient == null) return@withContext null
        if (processed >= maxImages) {
            skipped++
            return@withContext null
        }

        val file = runCatching { resolver.materialize(msg) }.getOrNull()
        if (file == null) {
            skipped++
            return@withContext null
        }
        processed++

        val desc = cached(file) { doVlm(file) } ?: return@withContext null
        hits++
        Insight("[图片 · $desc]", "AI")
    }

    private suspend fun doVlm(file: File): String? {
        val client = visionClient ?: return null
        val jpeg = BitmapUtil.toJpeg(file) ?: return null
        return runCatching {
            client.chatWithImage(
                system = "你是图像识别助手。只输出识别结果，用中文，不超过 120 字。" +
                    "看不清的地方直接说看不清，绝不编造。",
                user = "识别这张图，按下面三点简要说明：\n" +
                    "1. 这是什么类型的图（聊天截图 / 照片 / 文档 / 票据 / 商品 / 其他）\n" +
                    "2. 图里的关键文字：如果是截图或票据，准确摘录金额、单号、时间、地址、姓名等\n" +
                    "3. 画面的关键信息：如果是照片，说明场景和主体",
                image = jpeg,
                maxTokens = 400
            ).trim().ifBlank { null }
        }.onFailure { Log.w(TAG, "vlm failed for ${file.name}", it) }.getOrNull()
    }

    private suspend fun cached(file: File, compute: suspend () -> String?): String? {
        val key = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt"
        val c = File(cache, key)
        if (c.exists() && c.length() > 0) {
            return runCatching { c.readText() }.getOrNull()?.ifBlank { null }
        }
        val v = compute() ?: return null
        runCatching { c.writeText(v) }
        return v
    }

    private companion object {
        const val TAG = "WxBridgeImage"
    }
}
