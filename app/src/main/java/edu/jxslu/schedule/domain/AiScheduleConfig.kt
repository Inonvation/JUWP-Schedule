package edu.jxslu.schedule.domain

/**
 * AI 调课的默认配置（DESIGN §4.39，2026-10-06）。
 *
 * 默认走 DeepSeek 的 OpenAI 兼容端点，用户**只需填 API Key** 就能用；
 * 地址与模型名都可在设置里改（也允许清空自定义其它 OpenAI 兼容服务）。
 * 常量放这里而不是 UI / 数据层，是为了让偏好默认值、配置页、提示文案共用一个口径。
 */
object AiScheduleConfig {

    /** 预填的服务地址（OpenAI 兼容路径；DeepSeek 文档另列 `https://api.deepseek.com`，两者都通）。 */
    const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"

    /** 预填的模型名。现行官方名（DeepSeek-V4.1-Flash，2026-09-10 发布，原生多模态）；
     *  旧名 `deepseek-v4-flash` 只是已退役模型的 legacy 别名（仍被接受，但别当默认）。
     *  可手动改或从上游列表选。 */
    const val DEFAULT_MODEL = "deepseek-flash"

    /** 上游取不到列表时的备选提示（不强制，用户可手输任意模型名）。 */
    val MODEL_SUGGESTIONS = listOf("deepseek-flash", "deepseek-v4-pro")

    /** legacy 别名（其模型已退役，请求被 V4.1-Flash 服务）：读到它迁回现行默认。 */
    const val LEGACY_MODEL_ALIAS = "deepseek-v4-flash"

    /**
     * 模型名归一：legacy 别名迁到现行默认，空串给默认，其余原样。
     * 放这里（纯 JVM）是为了让 DisplayPrefsStore 的读取路径与单测共用一份口径。
     */
    fun normalizeModel(raw: String?): String {
        val name = raw?.trim().orEmpty()
        return when {
            name.isEmpty() -> DEFAULT_MODEL
            name.equals(LEGACY_MODEL_ALIAS, ignoreCase = true) -> DEFAULT_MODEL
            else -> name
        }
    }

    /**
     * 视觉模型的关键字（DESIGN §4.39，2026-10-06）。
     *
     * **只用来在配置页给「支持图片识别」打勾建议，绝不自动开启**：模型名是用户手填的，
     * 名字里带 `flash` 不等于带视觉，名字里不带也不等于没有。判定不了就交给用户。
     */
    private val VISION_HINTS = listOf(
        "-vl", "vision", "omni", "multimodal",
        "gpt-4o", "gpt-4.1", "gpt-5", "o3", "o4",
        "gemini", "claude-3", "claude-4", "claude-sonnet", "claude-opus", "claude-haiku",
        "glm-4v", "glm-4.5v", "qwen-vl", "qwen2-vl", "qwen2.5-vl",
        "internvl", "llava", "minicpm-v", "pixtral", "phi-3-vision",
        // deepseek-flash = DeepSeek-V4.1-Flash，官方 model card 标明多模态（文本 + 图片）；
        // 这里仍只是「建议打勾」，不自动开
        "deepseek-flash", "deepseek-v4-flash-vision",
    )

    /**
     * 模型名看着像视觉模型吗（**建议用，不作数**）。
     *
     * `deepseek-flash`（V4.1-Flash）按官方 model card 支持图片输入，所以在建议名单里；
     * 是否开启仍由用户决定——名字对得上不代表你配的网关吃图。
     */
    fun looksVisionModel(model: String): Boolean {
        val name = model.trim().lowercase()
        if (name.isEmpty()) return false
        return VISION_HINTS.any { name.contains(it) }
    }
}
