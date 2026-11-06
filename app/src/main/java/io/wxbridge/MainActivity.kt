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
    private lateinit var etGhToken: EditText

    private var pendingRelease: UpdateChecker.Release? = null
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

    /** 上次成功写入微信目录的自动回复配置，用来判断 onPause 时要不要再同步 */
    @Volatile
    private var lastSyncedJson: String? = null

    private val cfg by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        restoreAllUi()
        log("WxBridge v${BuildConfig.VERSION_NAME}")
        log("顺序：① 环境自检 → ② 读取密钥 → ③ 打开数据库 → ④ 生成摘要")
        log("配置改动会自动保存；自动回复写进微信目录，点「保存全部配置」才生效")
    }

    override fun onPause() {
        super.onPause()
        // 每次离开页面都落盘，避免「填完没点保存直接退出」丢配置
        persistAllUi()
        syncAutoReplyQuietly()
    }

    override fun onDestroy() {
        scope.cancel()
        wxDb?.close()
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        // 整页套一层 ScrollView。之前只给日志区套了 ScrollView，
        // 配置控件全堆在外面，控件一多底部就够不着了。
        val page = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 32)
        }
        page.addView(root)

        root.addView(actionRow("保存全部配置" to { saveAllConfig() }))

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

        root.addView(sectionTitle("更新"))
        etGhToken = EditText(this).apply {
            hint = "GitHub token（可选，私有仓库或触发限流时填）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(labeled("GitHub token", etGhToken))
        root.addView(actionRow(
            "检查更新" to { doCheckUpdate() },
            "下载并安装" to { doDownloadUpdate() }
        ))

        root.addView(actionRow(
            "抓 Xposed 日志" to { doGrabXposedLog() },
            "查看微信目录" to { doListWxDir() }
        ))

        root.addView(sectionTitle("运行日志"))
        logView = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            // 外层已经有 ScrollView，这里靠固定高度 + 自身滚动。
            // 触摸时临时禁止所有父级拦截，否则在日志区滑动会被外层抢走。
            setOnTouchListener { v, _ ->
                var p = v.parent
                while (p != null) {
                    p.requestDisallowInterceptTouchEvent(true)
                    p = p.parent
                }
                false
            }
        }
        root.addView(logView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(260)))

        root.addView(actionRow("清空日志" to {
            logBuf.setLength(0)
            logView.text = ""
        }))

        return page
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
            val hasRoot = withContext(Dispatchers.IO) { RootShell.hasRoot() }
            log("root: ${if (hasRoot) "OK" else "不可用"}")
            if (!hasRoot) {
                log("→ 没有 root，后面三步都走不通。先确认 Magisk/KernelSU 授权了本 App。")
                return@launchStep
            }
            val cleaned = withContext(Dispatchers.IO) { RootShell.cleanPhantom() }
            if (cleaned) log("已清理历史误建的空目录 /data/user/0/com.tencent.mm")
            val diag = withContext(Dispatchers.IO) { RootShell.diagnose() }
            log(diag)
        }
    }

    private fun doReadKey() {
        launchStep("读取密钥") {
            // RootShell 是阻塞的 su 调用，绝不能跑在主线程上
            val r = withContext(Dispatchers.IO) {
                WxKeyReader.read(
                    workDir = WxPaths.workDir(filesDir),
                    cacheFile = File(filesDir, "main_db.txt"),
                    onLog = { msg -> log(msg) }
                )
            }
            when (r) {
                is WxKeyReader.Result.Ok -> {
                    wxKey = r.key
                    wxDb = WxDatabase(filesDir, r.key)
                    log("dbPath = ${r.key.dbPath}")
                    log("主库   = ${r.key.dbName}  (${r.key.password.size} bytes)")
                    log("已抓库 = ${r.key.keys.size} 个：${r.key.keys.keys.joinToString { it.substringAfterLast('/') }.take(120)}")
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

        persistAllUi()

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
        launchStep("库结构扫描") {
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

    /** 从界面当前状态构建自动回复配置 */
    private fun currentAutoReplyConfig(): AutoReplyConfig {
        val whitelist = etWhitelist.text.toString()
            .split('\n', ',', '，', ' ', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return AutoReplyConfig(
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
    }

    private fun warnAutoReply(conf: AutoReplyConfig) {
        if (!conf.enabled) return
        if (conf.onlyWhitelist && conf.whitelist.isEmpty()) {
            log("！开了「只回白名单」但名单是空的，任何消息都不会回")
        }
        if (conf.replyGroupAt && conf.myGroupNickname.isBlank()) {
            log("！群聊 @ 回复必须填「我的群昵称」，否则群消息全部跳过")
        }
        if (conf.apiKey.isBlank()) log("！没有 API Key，回复会失败")
    }

    /** 顶部按钮：界面配置和自动回复配置一起落盘 */
    private fun saveAllConfig() {
        val conf = currentAutoReplyConfig()
        persistAllUi()
        warnAutoReply(conf)

        launchStep("保存配置") {
            val err = withContext(Dispatchers.IO) { AutoReplyStore.save(conf) }
            if (err == null) {
                lastSyncedJson = conf.toJson()
                log("界面配置已保存")
                log("自动回复配置已写入 ${AutoReplyPaths.cfgApp()}，微信进程约 4 秒内重新加载")
            } else {
                log("界面配置已保存（App 内），但写入微信目录失败：$err")
            }
        }
    }

    private fun saveAutoReply() {
        val conf = currentAutoReplyConfig()
        warnAutoReply(conf)

        launchStep("保存自动回复") {
            val err = withContext(Dispatchers.IO) { AutoReplyStore.save(conf) }
            persistAllUi()
            if (err == null) {
                lastSyncedJson = conf.toJson()
                log("已写入 ${AutoReplyPaths.cfgApp()}")
                log("微信进程约 4 秒内重新加载配置")
                if (conf.enabled) log("建议先把自己小号放进白名单试一条，别直接开全量")
            } else {
                log("写入失败：$err")
            }
        }
    }

    /**
     * onPause 时静默同步。
     * 只写 App 私有 prefs 是 Instant 的；写微信目录要 su，所以放在后台线程，
     * 失败也不打扰用户 —— 主路径仍然是「保存全部配置」按钮。
     */
    private fun syncAutoReplyQuietly() {
        val conf = runCatching { currentAutoReplyConfig() }.getOrNull() ?: return
        val json = conf.toJson()
        if (json == lastSyncedJson) return
        Thread {
            if (AutoReplyStore.save(conf) == null) lastSyncedJson = json
        }.apply { isDaemon = true }.start()
    }

    private fun showAutoReplyLog() {
        launchStep("回复日志") {
            val t = withContext(Dispatchers.IO) { AutoReplyStore.readLog(150) }
            log(if (t.isBlank()) "（日志为空：微信进程还没加载到配置，或模块未生效）" else t)
        }
    }

    /** 所有界面状态落盘到 App 自己的 SharedPreferences，不涉及 root，可以随时调用 */
    private fun persistAllUi() {
        val presetIndex = presetSpinner.selectedItemPosition
            .coerceIn(0, ModelPresets.ALL.size - 1)

        cfg.edit()
            .putString("preset", ModelPresets.ALL[presetIndex].id)
            .putString("base", etBase.text.toString().trim())
            .putString("key", etKey.text.toString().trim())
            .putString("textModel", etTextModel.text.toString().trim())
            .putString("visionModel", etVisionModel.text.toString().trim())
            .putInt("hours", etHours.text.toString().toIntOrNull() ?: 24)
            .putInt("maxImages", etMaxImages.text.toString().toIntOrNull() ?: 20)
            .putBoolean("vlm", cbVlm.isChecked)
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
            .putString("gh_token", etGhToken.text.toString().trim())
            .apply()
    }

    // ---------------- 更新 ----------------

    private fun doCheckUpdate() {
        launchStep("检查更新") {
            val token = etGhToken.text.toString().trim()
            cfg.edit().putString("gh_token", token).apply()

            log("当前版本 ${BuildConfig.VERSION_NAME}")

            when (val r = UpdateChecker.check(BuildConfig.VERSION_NAME, token)) {
                is UpdateChecker.Result.Newer -> {
                    pendingRelease = r.release
                    log("发现新版本：${r.release.title}")
                    if (r.release.sizeBytes > 0) {
                        log("安装包 ${r.release.sizeBytes / 1024 / 1024} MB")
                    }
                    if (r.release.notes.isNotBlank()) {
                        log("--- 更新说明 ---")
                        log(r.release.notes)
                    }
                    log("点「下载并安装」继续")
                }
                is UpdateChecker.Result.UpToDate -> {
                    pendingRelease = null
                    log("已是最新（${r.current}）")
                }
                is UpdateChecker.Result.Fail -> log(r.reason)
            }
        }
    }

    private fun doDownloadUpdate() {
        val rel = pendingRelease
        if (rel == null) {
            log("先点「检查更新」，确认有新版再下载")
            return
        }
        launchStep("下载更新") {
            log("开始下载 ${rel.apkName}")
            var lastBucket = -1
            val file = UpdateChecker.download(rel, this@MainActivity) { p ->
                val bucket = p / 10 * 10
                if (bucket != lastBucket) {
                    lastBucket = bucket
                    log("  $bucket%")
                }
            }
            if (file == null) {
                log("下载失败，检查网络或 token")
                return@launchStep
            }
            log("已下载 ${file.length() / 1024 / 1024} MB")
            log(UpdateChecker.install(this@MainActivity, file))
        }
    }

    /**
     * 直接抓 logcat 里我们自己的日志。
     *
     * 模块到底有没有被加载、hook 挂上没有、密码抓到没有，全在这一条流里，
     * 比让用户去 LSPosed 里翻日志快得多。root 下 logcat 能读全量。
     */
    private fun doGrabXposedLog() {
        launchStep("抓 Xposed 日志") {
            val r = withContext(Dispatchers.IO) {
                RootShell.exec(
                    "logcat -d -t 2000 2>/dev/null | grep -iE 'WxBridge|LSPosed|XposedBridge' | tail -120",
                    25
                )
            }
            if (r.out.isBlank()) {
                log("没抓到任何相关日志。可能是：")
                log("  · 模块没被 LSPosed 加载（检查「启用」和「作用域」）")
                log("  · 微信进程在模块安装前就启动了（要强制停止再开）")
                log("  · logcat 缓冲区已被刷掉（重新打开微信后立刻抓）")
                if (r.err.isNotBlank()) log("stderr: ${r.err.take(200)}")
            } else {
                log(r.out.trim())
            }
        }
    }

    /** 看微信目录里到底有没有生成文件，直接判断 hook 写没写 */
    private fun doListWxDir() {
        launchStep("查看微信目录") {
            val r = withContext(Dispatchers.IO) {
                RootShell.exec(
                    "echo '--- shared_prefs ---'; " +
                        "D=\$(ls -d /data/user/0/com.tencent.mm /data/data/com.tencent.mm 2>/dev/null | head -1); " +
                        "echo \"data dir = \$D\"; " +
                        "ls -l \"\$D/shared_prefs/\" 2>&1 | grep -i wxbridge; " +
                        "echo '--- files/wxbridge ---'; " +
                        "ls -l \"\$D/files/wxbridge/\" 2>&1",
                    15
                )
            }
            log(r.out.trim().ifBlank { "两个位置都没有 wxbridge 相关文件" })
        }
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

    private fun restoreAllUi() {
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
        etGhToken.setText(cfg.getString("gh_token", ""))

        // 首次启动（本地没有任何配置记录）时，尝试从微信目录导入。
        // 重装后 App 的 prefs 会清空，但微信那边的 cfg.json 还在跑，
        // 不导入的话 UI 会显示「已关闭」而实际仍在自动回复。
        if (!cfg.contains("preset")) {
            Thread {
                val remote = runCatching { AutoReplyStore.load() }.getOrNull()
                if (remote != null) runOnUiThread { applyImportedAutoReply(remote) }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun applyImportedAutoReply(c: AutoReplyConfig) {
        cbAuto.isChecked = c.enabled
        cbAutoPrivate.isChecked = c.replyPrivate
        cbAutoGroup.isChecked = c.replyGroupAt
        cbAutoWhitelistOnly.isChecked = c.onlyWhitelist
        cbAllowEmoji.isChecked = c.allowEmoji
        cbAutoSticker.isChecked = c.autoStickerReply
        etStickerChance.setText(c.stickerChance.toString())
        etMyNick.setText(c.myGroupNickname)
        etWhitelist.setText(c.whitelist.joinToString("\n"))
        etCtxCount.setText(c.contextCount.toString())
        etMinDelay.setText(c.minDelaySec.toString())
        etMaxDelay.setText(c.maxDelaySec.toString())
        etCooldown.setText(c.cooldownSec.toString())
        etDailyLimit.setText(c.dailyLimit.toString())

        persistAllUi()
        log("已从微信目录导入自动回复配置，和实际生效状态对齐")
    }
}
