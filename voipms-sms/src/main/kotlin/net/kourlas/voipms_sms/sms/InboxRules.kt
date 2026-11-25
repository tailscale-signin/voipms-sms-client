package net.kourlas.voipms_sms.sms

/** Conservative, on-device rules for unsolicited messages. Keep ordinary unknown senders in Main. */
internal object InboxRules {
    private val link = Regex("""(?:https?://|www\.|\b(?:bit\.ly|tinyurl\.com|t\.co)/)""", RegexOption.IGNORE_CASE)
    private val prize = Regex("""\b(?:you(?:'ve| have)? (?:won|been selected)|claim your (?:prize|reward|winnings)|lottery winner|free (?:gift card|cash prize))\b""", RegexOption.IGNORE_CASE)
    private val demand = Regex("""\b(?:claim|redeem|click|tap|pay|payment|verify|update your|confirm your|reschedule|fee)\b""", RegexOption.IGNORE_CASE)
    private val lure = Regex("""\b(?:unpaid (?:road )?tolls?|overdue tolls?|unclaimed (?:refund|funds)|(?:package|parcel|delivery) (?:is |was )?(?:on hold|held|undeliverable)|account (?:has been |is |will be )?(?:locked|suspended))\b""", RegexOption.IGNORE_CASE)

    fun suspectedJunk(latestIncoming: String?, hasReply: Boolean, knownContact: Boolean): Boolean {
        if (knownContact || hasReply || latestIncoming.isNullOrBlank()) return false
        return link.containsMatchIn(latestIncoming) && demand.containsMatchIn(latestIncoming) &&
            (prize.containsMatchIn(latestIncoming) || lure.containsMatchIn(latestIncoming))
    }
}
