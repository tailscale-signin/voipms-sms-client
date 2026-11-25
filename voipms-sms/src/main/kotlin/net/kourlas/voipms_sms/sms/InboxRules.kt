package net.kourlas.voipms_sms.sms

import java.net.IDN
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Pure, offline assessment. Message warnings and automatic conversation filing are separate decisions. */
internal object InboxRules {
    enum class Level { ORDINARY, CAUTION, HIGH }
    data class Assessment(val level: Level, val description: String? = null) {
        fun shouldQuarantine(hasReply: Boolean, knownContact: Boolean) =
            level == Level.HIGH && !hasReply && !knownContact
    }

    data class Profile(val phrases: Set<String> = emptySet(), val corrections: Map<String, Boolean> = emptyMap())

    private val ordinary = Assessment(Level.ORDINARY)
    private val url = Regex("""(?:https?://|www\.|\b(?:bit\.ly|tinyurl\.com|t\.co)/)[^\s<>"“”]+""", RegexOption.IGNORE_CASE)
    private val accents = Regex("""\p{M}+""")
    private val spaces = Regex("""\s+""")
    private val digits = Regex("""[0-9]+""")
    private val payment = Regex("""\b(?:pay|payment|fee|payer|payez|paiement|frais|pagar|pague|pagamento|taxa)\b""")
    private val action = Regex("""\b(?:claim|redeem|click|tap|verify|update your|confirm your|reschedule|cliqu\w*|reclam\w*|verifi\w*|confirm\w*|cliqu[ei]|resgat\w*|regulariz\w*)\b""")
    private val urgency = Regex("""\b(?:today|now|immediately|urgent\w*|within \d+ hours?|aujourd'hui|immediatement|maintenant|dernier avis|hoje|agora|imediatamente|ultimo aviso)\b""")
    private val lure = Regex(
        """\b(?:you(?:'ve| have)? (?:won|been selected)|claim your (?:prize|reward|winnings)|lottery winner|free (?:gift card|cash prize)|unpaid (?:road )?tolls?|overdue tolls?|unclaimed (?:refund|funds)|(?:package|parcel|delivery) (?:is |was )?(?:on hold|held|undeliverable)|account (?:has been |is |will be )?(?:locked|suspended)|peages? (?:impayes?|en retard)|colis (?:est )?(?:retenu|bloque|en attente)|compte (?:est )?(?:bloque|suspendu)|vous avez gagne|remboursement non reclame|(?:portage(?:m|ns)|pedagios?) (?:em atraso|pendentes?|nao pag[ao]s?)|encomenda (?:esta |foi )?(?:retida|bloqueada)|conta (?:foi |esta )?(?:suspensa|bloqueada)|voce (?:ganhou|foi selecionado))\b"""
    )
    private val request = Regex("""\b(?:send(?: me| us)?|share|forward|text (?:me|us)|reply with|tell (?:me|us)|give (?:me|us)|envoyez(?:-moi)?|envoie(?:-moi)?|partage[rz]?|transmet(?:tez|s)|donne[rz]?(?:-moi)?|envie|envia|mande|manda|compartilhe|partilhe|encaminhe|diga-me)\b""")
    private val secret = Regex("""\b(?:code|passcode|otp|pin|nip|codigo|one[- ]time password)\b""")
    private val notASecret = Regex("""\b(?:postal|zip|area|country|discount|coupon|promo|source|dress|vestimentaire|promocional|desconto|rastreio|rastreio de|rastreamento)\b""")
    private val negation = Regex("""\b(?:do not|don't|dont|never|must not|will never|jamais|ne|n'|nao|nunca)\b""")
    private val clause = Regex("""[.!?;\n]|\b(?:but|however|mais|porem|mas)\b""")

    // A label imitating one of these organizations can be inspected without visiting the URL.
    private val officialHosts = mapOf(
        "canadapost" to setOf("canadapost.ca", "canadapost-postescanada.ca"),
        "postescanada" to setOf("canadapost.ca", "canadapost-postescanada.ca"),
        "407etr" to setOf("407etr.com"), "interac" to setOf("interac.ca"),
        "ctt" to setOf("ctt.pt"), "laposte" to setOf("laposte.fr")
    )

    private fun fold(value: String): String = accents.replace(
        Normalizer.normalize(value, Normalizer.Form.NFKD), ""
    ).lowercase(Locale.ROOT).replace('’', '\'')

    private fun asksForCode(body: String): Boolean = clause.split(body).any { part ->
        request.findAll(part).any requestMatch@{ verb ->
            val before = part.substring((verb.range.first - 48).coerceAtLeast(0), verb.range.first)
            val after = part.substring(verb.range.last + 1).take(85)
            val code = secret.find(after) ?: return@requestMatch false
            val context = after.take(code.range.last + 25)
            !negation.containsMatchIn(before) && !negation.containsMatchIn(after.take(code.range.first)) &&
                !notASecret.containsMatchIn(context)
        }
    }

    private fun hostname(raw: String): String {
        val authority = raw.substringAfter("://", raw).substringBefore('/').substringBefore('?').substringBefore('#')
        return authority.substringAfterLast('@').substringBefore(':').trimEnd('.', ',', '!', ')', ']').lowercase(Locale.ROOT)
    }

    private fun official(raw: String) = hostname(raw).let { host ->
        officialHosts.values.any { roots -> roots.any { host == it || host.endsWith(".$it") } }
    }

    private fun misleading(raw: String): Boolean {
        val authority = raw.substringAfter("://", raw).substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) return true
        val host = hostname(raw)
        val unicode = IDN.toUnicode(host)
        return unicode.split('.').any { label ->
            val scripts = label.filter(Char::isLetter).map { Character.UnicodeScript.of(it.code) }.toSet()
            Character.UnicodeScript.LATIN in scripts &&
                (Character.UnicodeScript.CYRILLIC in scripts || Character.UnicodeScript.GREEK in scripts)
        } || officialHosts.any { (brand, allowed) ->
            val resembles = unicode.split('.').any { label ->
                fold(label).replace("-", "").let { it == brand || it.startsWith(brand) && label.contains('-') }
            }
            resembles && allowed.none { host == it || host.endsWith(".$it") }
        }
    }

    /** Learn recurring link campaigns on the same line. Never retain a message body or its URL query. */
    fun campaignKey(text: String): String? {
        val body = text.take(8192)
        if (body.length < 24 || !url.containsMatchIn(body) || VerificationCode.extract(body) != null) return null
        val normalized = buildString {
            var offset = 0
            for (match in url.findAll(body)) {
                append(digits.replace(fold(body.substring(offset, match.range.first)), "#"))
                append(' ').append(hostname(match.value)).append('/')
                val path = match.value.substringAfter("://", match.value).substringAfter('/', "")
                    .substringBefore('?').substringBefore('#')
                append(digits.replace(fold(path), "#")).append(' ')
                offset = match.range.last + 1
            }
            append(digits.replace(fold(body.substring(offset)), "#"))
        }.let { spaces.replace(it, " ").trim() }
        return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8)).toHexString()
    }

    fun assess(text: String?, phrases: Set<String> = emptySet()): Assessment {
        if (text.isNullOrBlank()) return ordinary
        val body = text.take(8192)
        // Keep casing for an exact, legible excerpt of the familiar English lure in the inbox.
        val normalized = fold(body)
        val codeRequest = asksForCode(normalized)
        if (codeRequest) {
            val deceptive = url.findAll(body).take(12).any { misleading(it.value) }
            return Assessment(if (deceptive) Level.HIGH else Level.CAUTION,
                if (deceptive) "Someone is requesting your verification code with a misleading link."
                else "Someone is asking for your verification code.")
        }
        val phrase = phrases.sorted().firstOrNull { body.contains(it, ignoreCase = true) }
        if (phrase != null) return Assessment(Level.HIGH, "Matched phrase “$phrase”")
        val links = url.findAll(body).take(12).toList()
        if (links.isEmpty()) return ordinary
        val pay = payment.containsMatchIn(normalized)
        val demand = pay || action.containsMatchIn(normalized)
        val oddHost = links.any { misleading(it.value) }
        if (oddHost) return Assessment(if (demand) Level.HIGH else Level.CAUTION,
            if (pay) "Payment request with a misleading link." else "The link has a misleading address.")
        val match = lure.find(normalized)
        if (demand && match != null) {
            val excerpt = body.substring(match.range.first.coerceAtMost(body.length), (match.range.last + 1).coerceAtMost(body.length))
            return Assessment(if (links.all { official(it.value) }) Level.CAUTION else Level.HIGH,
                "Link and request mentioning “$excerpt”")
        }
        if (pay && urgency.containsMatchIn(normalized)) return Assessment(Level.CAUTION, "Urgent payment requested through a link.")
        return ordinary
    }

    fun assess(text: String?, profile: Profile): Assessment {
        val result = assess(text, profile.phrases)
        if (text.isNullOrBlank() || profile.corrections.isEmpty()) return result
        return when (profile.corrections[campaignKey(text)]) {
            true -> Assessment(Level.HIGH, "Similar messages were moved to Other on this line.")
            false -> if (result.level == Level.HIGH) result.copy(level = Level.CAUTION) else result
            null -> result
        }
    }

    fun suspectedJunk(latestIncoming: String?, hasReply: Boolean, knownContact: Boolean) =
        assess(latestIncoming).shouldQuarantine(hasReply, knownContact)

    fun reason(text: String?, hasReply: Boolean, knownContact: Boolean, phrases: Set<String>): String? =
        assess(text, phrases).let { if (it.shouldQuarantine(hasReply, knownContact)) it.description else null }
}
