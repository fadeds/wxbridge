package io.wxbridge.llm

/**
 * 主流 OpenAI 兼容服务的预设。选一个就自动填好 baseUrl 和模型名，
 * 用户只需要贴 API Key。
 *
 * vision 为空表示该服务当前没有可用的视觉模型 —— 这种情况下
 * 图片只能靠本地 OCR（见 ImagePipeline），仍然能读到图里的文字。
 *
 * 注：DeepSeek 自 2026-08 起有原生多模态能力，deepseek-flash（V4.1-Flash）
 * 同时吃文本和图片；旧的 deepseek-v4-flash-vision-exp 已退役并路由到它。
 */
object ModelPresets {

    data class Preset(
        val id: String,
        val label: String,
        val baseUrl: String,
        val textModel: String,
        val visionModel: String,
        val note: String
    )

    val ALL: List<Preset> = listOf(
        Preset(
            id = "deepseek",
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            textModel = "deepseek-flash",
            visionModel = "deepseek-flash",
            note = "V4.1-Flash 原生多模态，文本和看图同一个模型；旧的 deepseek-chat 仍可用但不建议"
        ),
        Preset(
            id = "qwen",
            label = "通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            textModel = "qwen-plus",
            visionModel = "qwen-vl-plus",
            note = "中文场景首选，qwen-vl 识别截图和票据效果好"
        ),
        Preset(
            id = "zhipu",
            label = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            textModel = "glm-4-flash",
            visionModel = "glm-4v-flash",
            note = "glm-4-flash 免费额度大，适合先跑通"
        ),
        Preset(
            id = "moonshot",
            label = "Moonshot Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            textModel = "moonshot-v1-8k",
            visionModel = "moonshot-v1-8k-vision-preview",
            note = "长文本友好"
        ),
        Preset(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            textModel = "gpt-4o-mini",
            visionModel = "gpt-4o-mini",
            note = "需要海外网络"
        ),
        Preset(
            id = "local",
            label = "本地 ollama / vLLM",
            baseUrl = "http://127.0.0.1:11434/v1",
            textModel = "qwen2.5:7b",
            visionModel = "",
            note = "手机直连本机需同一局域网，IP 换成电脑地址；聊天记录不出内网"
        ),
        Preset(
            id = "custom",
            label = "自定义",
            baseUrl = "",
            textModel = "",
            visionModel = "",
            note = "任何 OpenAI 兼容端点"
        )
    )

    fun byId(id: String?): Preset? = ALL.firstOrNull { it.id == id }
}
