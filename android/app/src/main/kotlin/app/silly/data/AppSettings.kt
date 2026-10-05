package app.silly.data

/** 连接设置。 */
data class AppSettings(
    /** `openai` / `anthropic` / `gemini`。 */
    val providerId: String = "openai",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val maxTokens: Int = 1024,
    val temperature: Double = 0.8,
) {
    val isUsable: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()

    companion object {
        /** 切换 provider 时的推荐默认值。 */
        fun defaultsFor(providerId: String): AppSettings = when (providerId) {
            "anthropic" -> AppSettings(
                providerId = "anthropic",
                baseUrl = "https://api.anthropic.com",
                model = "claude-sonnet-4-5",
            )
            "gemini" -> AppSettings(
                providerId = "gemini",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                model = "gemini-2.5-flash",
            )
            else -> AppSettings()
        }

        val PROVIDERS = listOf(
            "openai" to "OpenAI 兼容（OpenAI / DeepSeek / OpenRouter / 本地）",
            "anthropic" to "Anthropic",
            "gemini" to "Google Gemini",
        )
    }
}
