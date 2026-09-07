package live.lyrica.app.ai

/**
 * Supported AI Providers for Translation and Transliteration.
 */
enum class AiProvider(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val defaultModel: String,
    val popularModels: List<String>
) {
    GROQ(
        id = "groq",
        displayName = "Groq",
        endpoint = "https://api.groq.com/openai/v1/chat/completions",
        defaultModel = "openai/gpt-oss-120b",
        popularModels = listOf(
            "openai/gpt-oss-120b",
            "groq/compound",
            "openai/gpt-oss-20b",
            "qwen/qwen3.8-27b"
        )
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        endpoint = "https://openrouter.ai/api/v1/chat/completions",
        defaultModel = "openrouter/free",
        popularModels = listOf(
            "openrouter/free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "google/gemini-2.0-flash-lite-preview-02-05:free",
            "deepseek/deepseek-r1:free",
            "qwen/qwen-2.5-coder-32b-instruct:free"
        )
    );

    companion object {
        fun fromId(id: String?): AiProvider = when (id?.lowercase()) {
            "openrouter" -> OPENROUTER
            else -> GROQ
        }
    }
}

/**
 * Display modes for lyrics in UI.
 */
enum class LyricsDisplayMode(val displayName: String) {
    ORIGINAL("Original"),
    TRANSLATE("Translate"),
    TRANSLITERATE("Romanize"),
    DUAL("Dual")
}

/**
 * Pre-populated popular target languages list.
 */
object AiLanguages {
    val POPULAR_LANGUAGES = listOf(
        "English",
        "Spanish",
        "Hindi",
        "French",
        "German",
        "Japanese",
        "Korean",
        "Chinese",
        "Arabic",
        "Russian",
        "Portuguese",
        "Italian",
        "Punjabi",
        "Bengali",
        "Turkish",
        "Vietnamese"
    )
}
