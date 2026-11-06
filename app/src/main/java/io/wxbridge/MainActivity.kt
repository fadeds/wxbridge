package io.wxbridge

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.wxbridge.db.ImageResolver
import io.wxbridge.db.WxDatabase
import io.wxbridge.llm.LlmClient
import io.wxbridge.llm.ModelPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 调试台。四步流水线，每一步挂了日志里能直接看到原因。
 * 刻意不用 Compose —— 少一层依赖，模块 APK 越薄越好。
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var presetSpinner: Spinner
    private lateinit var presetNote: TextView
    private lateinit var etBase: EditText
    private lateinit var etKey: EditText
    private lateinit var etTextModel: EditText
    private lateinit var etVisionModel: EditText
    private lateinit var etHours: EditText
    private lateinit var etMaxImages: EditText
    private lateinit var cbVlm: CheckBox

    private lateinit var cbAuto: CheckBox
    private lateinit var cbAutoPrivate: CheckBox
    private lateinit var cbAutoGroup: CheckBox
    private lateinit var cbAutoWhitelistOnly: CheckBox
    private lateinit var cbAllowEmoji: CheckBox
    private lateinit var cbAutoSticker: CheckBox
    private lateinit var etStickerChance: EditText
    private lateinit var etWhitelist: EditText
    private lateinit var etMyNick: EditText
    private lateinit var etCtxCount: EditText
    private lateinit var etMinDelay: EditText
    private lateinit var etMaxDelay: EditText
    private lateinit var etCooldown: EditText
    private lateinit var etDailyLimit: EditText

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val logBuf = StringBuilder()

    private var wxDb: WxDatabase? = null
    private var wxKey: WxKeyReader.WxKey? = null
    private var busy = false
    private var suppressPresetCallback = false

    private val cfg by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        restoreConfig()
        log("WxBridge v${BuildConfig.VERSION_NAME}")
        log("顺序：① 环境自检 → ② 读取密钥 → ③ 打开数据库 → ④ 生成摘要")
    }

    override fun onDestroy() {
        scope.cancel()
        wxDb?.close()
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 32)
        }

        root.addView(sectionTitle("模型"))
        presetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                ModelPresets.ALL.map { it.label }
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (suppressPresetCallback) return
                    applyPreset(ModelPresets.ALL[pos])
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        presetNote = TextView(this).apply { textSize = 11f; setPadding(0, 4, 0, 8) }

        etBase = EditText(this).apply {
            hint = "https://api.deepseek.com/v1"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        etKey = EditText(this).apply {
            hint = "sk-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        etTextModel = EditText(this).apply { hint = "deepseek-flash" }
        etVisionModel = EditText(this).apply { hint = "留空 = 不用视觉模型" }

        root.addView(presetSpinner)
        root.addView(presetNote)
        root.addView(labeled("Base URL", etBase))
        root.addView(labeled("API Key", etKey))
        root.addView(labeled("文本模型（摘要用）", etTextModel))
        root.addView(labeled("视觉模型（看图用，可空）", etVisionModel))

        root.addView(sectionTitle("图片理解"))
        cbVlm = CheckBox(this).apply {
            text = "用 AI 识别图片（读字 + 看画面，走视觉模型）"
            isChecked = true
        }
        etMaxImages = EditText(this).apply {
            hint = "20"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        etHours = EditText(this).apply {
            hint = "24"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(cbVlm)
        root.addView(labeled("每次最多分析几张图", etMaxImages))
        root.addView(labeled("摘要时间窗（小时）", etHours))

        root.addView(actionRow(
            "① 环境自检" to { doCheckEnv() },
            "② 读取密钥" to { doReadKey() }
        ))
        root.addView(actionRow(
            "③ 打开数据库" to { doOpenDb() },
            "④ 生成摘要" to { doSummarize() }
        ))
        root.addView(actionRow(
            "⑤ 库结构扫描" to { doScanDbs() }
        ))

        root.addView(sectionTitle("自动回复（写操作 · 有封号风险）"))
        cbAuto = CheckBox(this).apply { text = "启用自动回复"; isChecked = false }
        cbAutoPrivate = CheckBox(this).apply { text = "私聊直接回"; isChecked = true }
        cbAutoGroup = CheckBox(this).apply { text = "群聊仅在被我 @ 时回"; isChecked = true }
        cbAutoWhitelistOnly = CheckBox(this).apply { text = "只回白名单（强烈建议开着）"; isChecked = true }
        cbAllowEmoji = CheckBox(this).apply { text = "允许回复里带 emoji（文本直发，不用模拟点击）"; isChecked = true }
        cbAutoSticker = CheckBox(this).apply {
            text = "对方发表情包时，回一个本地表情包"
            isChecked = true
        }
        etStickerChance = EditText(this).apply {
            hint = "50"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        etMyNick = EditText(this).apply { hint = "群里 @ 你时用的昵称，必填" }
        etWhitelist = EditText(this).apply {
            hint = "白名单，一行一个：wxid_xxx 或备注名片段"
            minLines = 2
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        etCtxCount = EditText(this).apply { hint = "10"; inputType = InputType.TYPE_CLASS_NUMBER }
        etMinDelay = EditText(this).apply { hint = "5"; inputType = InputType.TYPE_CLASS_NUMBER }
        etMaxDelay = EditText(this).apply { hint = "12"; inputType = InputType.TYPE_CLASS_NUMBER }
        etCooldown = EditText(this).apply { hint = "180"; inputType = InputType.TYPE_CLASS_NUMBER }
        etDailyLimit = EditText(this).apply { hint = "100"; inputType = InputType.TYPE_CLASS_NUMBER }

        root.addView(cbAuto)
        root.addView(cbAutoPrivate)
        root.addView(cbAutoGroup)
        root.addView(cbAutoWhitelistOnly)
        root.addView(cbAllowEmoji)
        root.addView(cbAutoSticker)
        root.addView(labeled("回表情概率 %（0-100，群里不生效）", etStickerChance))
        root.addView(labeled("我的群昵称", etMyNick))
        root.addView(labeled("白名单", etWhitelist))

        val nums = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val w1 = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        nums.addView(labeled("上下文", etCtxCount), w1)
        nums.addView(labeled("延迟低", etMinDelay), w1)
        nums.addView(labeled("延迟高", etMaxDelay), w1)
        nums.addView(labeled("冷却s", etCooldown), w1)
        nums.addView(labeled("日上限", etDailyLimit), w1)
        root.addView(nums)

        root.addView(actionRow(
            "保存自动回复" to { saveAutoReply() },
            "回复日志" to { showAutoReplyLog() }
        ))

        logView = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(0, 24, 0, 0)
        }
        root.addView(
            ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )

        return root
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setPadding(0, 20, 0, 6)
    }

    private fun labeled(name: String, field: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply { text = name; textSize = 12f })
        addView(field)
    }

    private fun actionRow(vararg actions: Pair<String, () -> Unit>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        actions.forEach { (name, fn) ->
            addView(Button(this@MainActivity).apply {
                text = name
                textSize = 12f
                setOnClickListener { if (!busy) fn() }
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun applyPreset(p: ModelPresets.Preset) {
        presetNote.text = p.note
        if (p.id == "custom") return
        etBase.setText(p.baseUrl)
        etTextModel.setText(p.textModel)
        etVisionModel.setText(p.visionModel)
        cbVlm.isChecked = p.visionModel.isNotBlank()
    }

    // ---------------- 流水线 ----------------

    private fun doCheckEnv() {
        launchStep("环境自检") {
            log("root: ${if (RootShell.hasRoot()) "OK" else "不可用"}")
            if (!RootShell.hasRoot()) {
                log("→ 没有 root，后面三步都走不通。先确认 Magisk/KernelSU 授权了本 App。")
                return@launchStep
            }
            val ls = RootShell.listDir(WxPaths.MICRO_MSG)
            log("${WxPaths.MICRO_MSG} 下有 ${ls.size} 项")
            if (ls.isEmpty()) {
                log("→ 读不到 MicroMsg，检查微信是否已登录过、以及 root 是否真的生效")
            } else {
                log("样例: ${ls.take(6).joinToString(", ")}")
            }
            val xml = RootShell.readText("/data/data/com.tencent.mm/shared_prefs/wxbridge.xml")
            log(
                if (xml == null) {
                    "hook 尚未写入密码 → 去 LSPosed 启用模块并勾选微信作用域，然后冷启动微信"
                } else {
                    "hook 已写入密码（${xml.length} 字节）"
                }
            )
        }
    }

    private fun doReadKey() {
        launchStep("读取密钥") {
            when (val r = WxKeyReader.read()) {
                is WxKeyReader.Result.Ok -> {
                    wxKey = r.key
                    wxDb = WxDatabase(filesDir, r.key)
                    log("dbPath = ${r.key.dbPath}")
                    log("pwd    = ${r.key.passwordUtf8.ifBlank { "(二进制)" }}  (${r.key.password.size} bytes)")
                    log("已就绪，接着点③")
                }
                is WxKeyReader.Result.Fail -> log(r.reason)
            }
        }
    }

    private fun doOpenDb() {
        val db = wxDb
        if (db == null) {
            log("先点②读取密钥")
            return
        }
        launchStep("打开数据库") {
            log(withContext(Dispatchers.IO) { db.refresh() })
            val repo = db.repo() ?: return@launchStep
            if (!repo.hasMessageTable()) {
                log("！message 表不存在，可能库结构变了")
                return@launchStep
            }
            val cols = repo.columnsOf("message").sorted()
            log("message 表字段(${cols.size}): ${cols.take(12).joinToString(", ")}…")
            val maxT = repo.maxCreateTime()
            log("最新消息时间: ${if (maxT > 0) java.util.Date(maxT * 1000) else "空库"}")

            val sample = repo.loadSince(maxT - 86400, 8)
            val imgCount = sample.count { it.type == io.wxbridge.db.MsgType.IMAGE }
            log("近 24h 抽样 ${sample.size} 条，其中图片 $imgCount 张")
            log("--- 预览 ---")
            log(sample.preview(8).ifBlank { "(无)" })
        }
    }

    private fun doSummarize() {
        val db = wxDb
        val key = wxKey
        if (db == null || key == null) {
            log("先点②③")
            return
        }

        val base = etBase.text.toString().trim()
        val apiKey = etKey.text.toString().trim()
        val textModel = etTextModel.text.toString().trim()
        val visionModel = etVisionModel.text.toString().trim()
        val hours = etHours.text.toString().trim().toIntOrNull() ?: 24
        val maxImages = etMaxImages.text.toString().trim().toIntOrNull() ?: 20

        if (base.isBlank() || apiKey.isBlank() || textModel.isBlank()) {
            log("Base URL / API Key / 文本模型 都要填")
            return
        }

        saveConfig(base, apiKey, textModel, visionModel, hours, maxImages)

        launchStep("生成摘要") {
            val useVision = cbVlm.isChecked && visionModel.isNotBlank()
            if (cbVlm.isChecked && visionModel.isBlank()) {
                log("！勾了 AI 识图但没填视觉模型，本次跳过所有图片")
            }
            log("配置：文本=$textModel，视觉=${if (useVision) visionModel else "关（图片跳过）"}，最多 $maxImages 张图")
            log("时间窗 ${hours}h，脱敏后发送…")

            val llm = LlmClient(base, apiKey, textModel)
            val pipeline = if (useVision) {
                val imageDb = withContext(Dispatchers.IO) { db.openExtra("imageinfo2.db") }
                val resolver = ImageResolver(imageDb, key.dbDir, WxPaths.workDir(filesDir))
                ImagePipeline(
                    resolver = resolver,
                    cacheRoot = File(cacheDir, "img"),
                    visionClient = LlmClient(base, apiKey, visionModel),
                    maxImages = maxImages
                )
            } else null

            val out = SyncEngine(this@MainActivity, db).summarize(hours, llm, pipeline)
            log("已处理 ${out.messageCount} 条 / ${out.groupCount} 个会话")
            if (out.imageStats.isNotBlank()) log("图片：${out.imageStats}")
            log("======== 摘要 ========")
            log(out.text)
        }
    }

    /**
     * 扫描库目录下所有 .db 并列出表名。
     *
     * 用途：确认 rcontact、ImgInfo2 这些表到底在哪个库里 ——
     * 微信 8.x 后期把表从 EnMicroMsg.db 拆了出去，
     * 图片定位和联系人名解析都依赖这个判断。顺带也能看出微信有没有
     * 把图片文字索引到本地（FTS / search 类表）。
     */
    private fun doScanDbs() {
        val db = wxDb
        if (db == null) {
            log("先点②读取密钥，这一步要用密码解密各个库")
            return
        }
        launchStep("探测 OCR 索引") {
            val scans = withContext(Dispatchers.IO) { db.scanAllDbs() }
            var hit = false
            for (s in scans) {
                if (!s.opened) {
                    log("✗ ${s.file}: ${s.error}")
                    continue
                }
                val interesting = s.tables.filter { t ->
                    val l = t.lowercase()
                    l.contains("fts") || l.contains("ocr") ||
                        l.contains("search") || l.contains("index")
                }
                if (interesting.isNotEmpty()) hit = true
                val mark = if (interesting.isEmpty()) "" else "  ★ ${interesting.joinToString(", ")}"
                log("${s.file}：${s.tables.size} 张表$mark")
            }
            log("")
            log("重点看 rcontact、ImgInfo2 落在哪个库 —— 图片定位和联系人名解析依赖它。")
            if (hit) log("另外发现 FTS/search 相关表，说明微信可能把图片文字索引到了本地。")
        }
    }

    private fun saveAutoReply() {
        val whitelist = etWhitelist.text.toString()
            .split('\n', ',', '，', ' ', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val conf = AutoReplyConfig(
            enabled = cbAuto.isChecked,
            baseUrl = etBase.text.toString().trim(),
            apiKey = etKey.text.toString().trim(),
            model = etTextModel.text.toString().trim(),
            replyPrivate = cbAutoPrivate.isChecked,
            replyGroupAt = cbAutoGroup.isChecked,
            onlyWhitelist = cbAutoWhitelistOnly.isChecked,
            whitelist = whitelist,
            myGroupNickname = etMyNick.text.toString().trim(),
            contextCount = etCtxCount.text.toString().toIntOrNull() ?: 10,
            minDelaySec = etMinDelay.text.toString().toIntOrNull() ?: 5,
            maxDelaySec = etMaxDelay.text.toString().toIntOrNull() ?: 12,
            cooldownSec = etCooldown.text.toString().toIntOrNull() ?: 180,
            dailyLimit = etDailyLimit.text.toString().toIntOrNull() ?: 100,
            allowEmoji = cbAllowEmoji.isChecked,
            autoStickerReply = cbAutoSticker.isChecked,
            stickerChance = etStickerChance.text.toString().toIntOrNull() ?: 50
        )

        if (conf.enabled && conf.onlyWhitelist && whitelist.isEmpty()) {
            log("！开了「只回白名单」但名单是空的，任何消息都不会回")
        }
        if (conf.enabled && conf.replyGroupAt && conf.myGroupNickname.isBlank()) {
            log("！群聊 @ 回复必须填「我的群昵称」，否则群消息全部跳过")
        }
        if (conf.enabled && conf.apiKey.isBlank()) {
            log("！没有 API Key，回复会失败")
        }

        launchStep("保存自动回复") {
            val ok = withContext(Dispatchers.IO) { AutoReplyStore.save(conf) }
            persistAutoReplyUi()
            if (ok) {
                log("已写入 ${AutoReplyPaths.CFG}")
                log("微信进程约 4 秒内重新加载配置")
                if (conf.enabled) log("建议先把自己小号放进白名单试一条，别直接开全量")
            } else {
                log("写入失败 —— 检查 root 授权是否给了本 App")
            }
        }
    }

    private fun showAutoReplyLog() {
        launchStep("回复日志") {
            val t = withContext(Dispatchers.IO) { AutoReplyStore.readLog(150) }
            log(if (t.isBlank()) "（日志为空：微信进程还没加载到配置，或模块未生效）" else t)
        }
    }

    private fun persistAutoReplyUi() {
        cfg.edit()
            .putBoolean("ar_enabled", cbAuto.isChecked)
            .putBoolean("ar_private", cbAutoPrivate.isChecked)
            .putBoolean("ar_group", cbAutoGroup.isChecked)
            .putBoolean("ar_wl_only", cbAutoWhitelistOnly.isChecked)
            .putBoolean("ar_emoji", cbAllowEmoji.isChecked)
            .putBoolean("ar_sticker", cbAutoSticker.isChecked)
            .putInt("ar_sticker_chance", etStickerChance.text.toString().toIntOrNull() ?: 50)
            .putString("ar_nick", etMyNick.text.toString().trim())
            .putString("ar_whitelist", etWhitelist.text.toString())
            .putInt("ar_ctx", etCtxCount.text.toString().toIntOrNull() ?: 10)
            .putInt("ar_min", etMinDelay.text.toString().toIntOrNull() ?: 5)
            .putInt("ar_max", etMaxDelay.text.toString().toIntOrNull() ?: 12)
            .putInt("ar_cool", etCooldown.text.toString().toIntOrNull() ?: 180)
            .putInt("ar_limit", etDailyLimit.text.toString().toIntOrNull() ?: 100)
            .apply()
    }

    // ---------------- 基础设施 ----------------

    private fun launchStep(title: String, block: suspend () -> Unit) {
        busy = true
        log("")
        log("▶ $title")
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                log("✗ ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            logBuf.append(msg).append('\n')
            if (logBuf.length > 60_000) logBuf.delete(0, logBuf.length - 40_000)
            logView.text = logBuf
        }
    }

    private fun restoreConfig() {
        val presetId = cfg.getString("preset", "deepseek") ?: "deepseek"
        val idx = ModelPresets.ALL.indexOfFirst { it.id == presetId }.coerceAtLeast(0)

        suppressPresetCallback = true
        presetSpinner.setSelection(idx)
        suppressPresetCallback = false
        presetNote.text = ModelPresets.ALL[idx].note

        etBase.setText(cfg.getString("base", ModelPresets.ALL[idx].baseUrl))
        etKey.setText(cfg.getString("key", ""))
        etTextModel.setText(cfg.getString("textModel", ModelPresets.ALL[idx].textModel))
        etVisionModel.setText(cfg.getString("visionModel", ModelPresets.ALL[idx].visionModel))
        etHours.setText(cfg.getInt("hours", 24).toString())
        etMaxImages.setText(cfg.getInt("maxImages", 20).toString())
        // 首次启动时跟随预设：模型有视觉能力就默认开
        cbVlm.isChecked = cfg.getBoolean("vlm", ModelPresets.ALL[idx].visionModel.isNotBlank())

        cbAuto.isChecked = cfg.getBoolean("ar_enabled", false)
        cbAutoPrivate.isChecked = cfg.getBoolean("ar_private", true)
        cbAutoGroup.isChecked = cfg.getBoolean("ar_group", true)
        cbAutoWhitelistOnly.isChecked = cfg.getBoolean("ar_wl_only", true)
        cbAllowEmoji.isChecked = cfg.getBoolean("ar_emoji", true)
        cbAutoSticker.isChecked = cfg.getBoolean("ar_sticker", true)
        etStickerChance.setText(cfg.getInt("ar_sticker_chance", 50).toString())
        etMyNick.setText(cfg.getString("ar_nick", ""))
        etWhitelist.setText(cfg.getString("ar_whitelist", ""))
        etCtxCount.setText(cfg.getInt("ar_ctx", 10).toString())
        etMinDelay.setText(cfg.getInt("ar_min", 5).toString())
        etMaxDelay.setText(cfg.getInt("ar_max", 12).toString())
        etCooldown.setText(cfg.getInt("ar_cool", 180).toString())
        etDailyLimit.setText(cfg.getInt("ar_limit", 100).toString())
    }

    private fun saveConfig(
        base: String, key: String, textModel: String,
        visionModel: String, hours: Int, maxImages: Int
    ) {
        cfg.edit()
            .putString("preset", ModelPresets.ALL[presetSpinner.selectedItemPosition].id)
            .putString("base", base)
            .putString("key", key)
            .putString("textModel", textModel)
            .putString("visionModel", visionModel)
            .putInt("hours", hours)
            .putInt("maxImages", maxImages)
            .putBoolean("vlm", cbVlm.isChecked)
            .apply()
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }
}
