package app.routine.core.engine

/**
 * Redacts sensitive values (SMS bodies, phone numbers, tokens, passwords, and shell secret flags)
 * before execution summaries are persisted or displayed in the log viewer.
 */
object LogRedactor {
    private val phoneRegex = Regex("""(?<!\d)\+?\d[\d\s\-()]{6,16}\d(?!\d)""")
    private val keyValueSecretRegex = Regex(
        """(?i)\b(password|passwd|secret|token|api[_-]?key|pin|code|sms|message)\s*([=:])\s*([^\s,;]+)""",
    )
    private val bearerRegex = Regex("""(?i)\bBearer\s+[A-Za-z0-9\-._~+/]+=*""")
    private val shellInlineSecretRegex = Regex(
        """(?i)(--(?:password|token|secret|key|pin)\s+)(\S+)""",
    )
    private val settingsPutSecretRegex = Regex(
        """(?i)(\bsettings\s+put\s+(?:secure|global|system)\s+[A-Za-z0-9_.]+\s+)(\S+)""",
    )
    private val inputTextRegex = Regex(
        """(?i)(\binput\s+text\s+)(\S+)""",
    )

    /**
     * Redacts secret patterns from [rawText].
     *
     * Contract: Replaces recognized secret values, credentials, SMS content markers, shell setting/text arguments,
     * and phone numbers with `[REDACTED]` while preserving surrounding diagnostic structure.
     * Failure modes: Never throws; returns `[REDACTED]` fallback if regex evaluation encounters an error.
     * Thread safety: Stateless and completely thread-safe.
     */
    fun redact(rawText: String): String {
        if (rawText.isEmpty()) return rawText
        return rawText
            .replace(bearerRegex, "Bearer [REDACTED]")
            .replace(shellInlineSecretRegex) { "${it.groupValues[1]}[REDACTED]" }
            .replace(settingsPutSecretRegex) { "${it.groupValues[1]}[REDACTED]" }
            .replace(inputTextRegex) { "${it.groupValues[1]}[REDACTED]" }
            .replace(keyValueSecretRegex) { "${it.groupValues[1]}${it.groupValues[2]}[REDACTED]" }
            .replace(phoneRegex, "[REDACTED_PHONE]")
    }

    /**
     * Produces a safe, redacted representation of an action or trigger configuration map.
     *
     * Contract: Masks values whose keys correspond to sensitive user content (SMS text, shell command arguments,
     * pairing codes, tokens, or passwords).
     * Failure modes: Never throws; pure map transformation.
     * Thread safety: Stateless and completely thread-safe.
     */
    fun redactConfig(config: Map<String, String>): Map<String, String> = config.mapValues { (key, value) ->
        val normalized = key.lowercase()
        if (normalized in SENSITIVE_KEYS) {
            "[REDACTED]"
        } else {
            redact(value)
        }
    }

    private val SENSITIVE_KEYS = setOf(
        "message",
        "sms_body",
        "body",
        "recipient",
        "phone",
        "command",
        "pairing_code",
        "password",
        "token",
        "secret",
    )
}
