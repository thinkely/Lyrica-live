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
        defaultModel = "llama-3.3-70b-versatile",
        popularModels = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "mixtral-8x7b-32768",
            "gemma2-9b-it"
        )
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        endpoint = "https://openrouter.ai/api/v1/chat/completions",
        defaultModel = "google/gemini-2.0-flash-001",
        popularModels = listOf(
            "google/gemini-2.0-flash-001",
            "deepseek/deepseek-chat",
            "openai/gpt-4o-mini",
            "meta-llama/llama-3.3-70b-instruct"
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
