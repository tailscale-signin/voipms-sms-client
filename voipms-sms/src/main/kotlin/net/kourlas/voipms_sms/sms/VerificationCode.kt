package net.kourlas.voipms_sms.sms

internal object VerificationCode {
    private const val MARKER = """(?<!zip )(?<!postal )(?<!area )(?<!country )(?<!error )(?:verification\s+code|security\s+code|confirmation\s+code|login\s+code|access\s+code|one[- ]time\s+(?:password|passcode|code|pin)|code\s+(?:de\s+)?(?:vérification|sécurité|confirmation)|código\s+de\s+(?:verificação|segurança|acesso)|code|passcode|otp|pin|nip)"""
    private const val VALUE = """(?<![\p{L}\p{N}/.+-])(?:G-)?([0-9]{3}[ -][0-9]{3}|[0-9]{4}[ -][0-9]{4}|[A-Z0-9]{4}-[A-Z0-9]{4}|[A-Z0-9]{4,8})(?![\p{L}\p{N}/-]|[. -][0-9])"""
    private val following = Regex("""\b$MARKER\b(?:\s+\(OTP\))?(?:\s+(?:is|est(?: le)?|é))?[\s:#=–—-]*$VALUE""", RegexOption.IGNORE_CASE)
    private val preceding = Regex("""$VALUE\s+(?:is|est|é)\s+(?:(?:your|votre|the|seu)\s+)?(?:[\p{L}][\p{L}'’-]*\s+){0,3}$MARKER\b""", RegexOption.IGNORE_CASE)
    private val instruction = Regex("""\b(?:use|enter)\s+$VALUE\s+to\s+(?:verify|sign in|log in|authenticate|reset)\b""", RegexOption.IGNORE_CASE)
    private const val HOST = """(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}"""
    // Origin-bound SMS places the origin and code together on the last line.
    private val originBound = Regex("""@$HOST #([A-Z0-9]{4,8})(?: @$HOST)?""", RegexOption.IGNORE_CASE)
    private val digitsOnly = Regex("""[0-9]{4,8}""")

    // Deletion needs an explicit authentication context. A naked number may be a price or reference.
    private val authentication = Regex("""\b(?:verification|security|login|sign[- ]in|one[- ]time|authentication|otp|passcode|vérification|sécurité|verificação|segurança)\b""", RegexOption.IGNORE_CASE)

    private val personalCode = Regex("""\b(?:your (?:code|pin)|votre (?:code|nip)|seu (?:código|pin))\s*(?:is|est|é|:|=)""", RegexOption.IGNORE_CASE)

    fun shouldMaskNotification(text: String) = eligibleForRetention(text) ||
        (personalCode.containsMatchIn(text) && extract(text) != null)

    fun eligibleForRetention(text: String) = (authentication.containsMatchIn(text) ||
        originBound.matchEntire(text.trim().substringAfterLast('\n')) != null) && extract(text) != null

    fun extract(text: String): String? {
        val trimmed = text.trim()
        if (digitsOnly.matches(trimmed)) return trimmed
        val lastLine = trimmed.substringAfterLast('\n')
        val matches = following.findAll(trimmed) + preceding.findAll(trimmed) + instruction.findAll(trimmed) +
            listOfNotNull(originBound.matchEntire(lastLine)).asSequence()
        val codes = matches.map { match ->
            val token = match.groupValues[1]
            if (token.any { it.isLetter() }) token else token.replace(" ", "").replace("-", "")
        }.filter { code -> code.any { it in '0'..'9' } }.distinct().take(2).toList()
        return codes.singleOrNull()
    }
}
