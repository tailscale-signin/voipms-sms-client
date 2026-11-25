package net.kourlas.voipms_sms.sms

internal object VerificationCode {
    private const val MARKER = """(?<!zip )(?<!postal )(?<!area )(?<!country )(?<!error )(?:verification\s+code|security\s+code|confirmation\s+code|login\s+code|access\s+code|one[- ]time\s+(?:password|passcode|code|pin)|code\s+(?:de\s+)?(?:vérification|sécurité|confirmation)|code|passcode|otp|pin)"""
    private const val VALUE = """(?<![\p{L}\p{N}/.+-])(?:G-)?([0-9]{3}[ -][0-9]{3}|[0-9]{4}[ -][0-9]{4}|[A-Z0-9]{4}-[A-Z0-9]{4}|[A-Z0-9]{4,8})(?![\p{L}\p{N}/-]|[. -][0-9])"""
    private val following = Regex("""\b$MARKER\b(?:\s+(?:is|est))?[\s:#=–—-]*$VALUE""", RegexOption.IGNORE_CASE)
    private val preceding = Regex("""$VALUE\s+(?:is|est)\s+(?:(?:your|votre|the)\s+)?(?:[\p{L}][\p{L}'’-]*\s+){0,3}$MARKER\b""", RegexOption.IGNORE_CASE)
    private val instruction = Regex("""\b(?:use|enter)\s+$VALUE\s+to\s+(?:verify|sign in|log in|authenticate|reset)\b""", RegexOption.IGNORE_CASE)
    private val digitsOnly = Regex("""[0-9]{4,8}""")

    fun extract(text: String): String? {
        val trimmed = text.trim()
        if (digitsOnly.matches(trimmed)) return trimmed
        val codes = (following.findAll(trimmed) + preceding.findAll(trimmed) + instruction.findAll(trimmed))
            .map { match ->
                val token = match.groupValues[1]
                if (token.any { it.isLetter() }) token else token.replace(" ", "").replace("-", "")
            }
            .filter { code -> code.any { it in '0'..'9' } }
            .distinct().take(2).toList()
        return codes.singleOrNull()
    }
}
