package com.sovereignops.sms.core.address

/**
 * What a Telephony-provider address actually is. Android's shared message store keeps
 * whatever string the app that wrote a thread gave it (`canonical_addresses`), so an
 * "address" can be a phone number, a short code, an email (MMS), an alphanumeric sender
 * name, a URI, or an opaque identifier such as another messenger's chat ID.
 */
enum class AddressKind { PHONE, SHORT_CODE, EMAIL, ALPHA_SENDER, URI, OPAQUE, EMPTY }

object AddressClassifier {
    private val formatting = Regex("[\\s\\-.()]")
    private val email = Regex("^[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}$")
    private val uri = Regex("^(sips?|tel|mailto|rcs|urn|im|pres):", RegexOption.IGNORE_CASE)
    private val serviceCode = Regex("^[*#]\\d{2,6}#?$")
    // GSM alphanumeric sender IDs are at most 11 characters (3GPP TS 23.040).
    private val alphaSender = Regex("^[A-Za-z0-9 .\\-&_!']{1,11}$")
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val hex = Regex("^[0-9a-fA-F]+$")
    private val alnum = Regex("^[A-Za-z0-9]+$")

    fun classify(raw: String): AddressKind {
        val s = raw.trim()
        if (s.isEmpty()) return AddressKind.EMPTY
        if (uri.containsMatchIn(s)) return AddressKind.URI
        if (email.matches(s)) return AddressKind.EMAIL
        if (serviceCode.matches(s)) return AddressKind.SHORT_CODE
        val compact = s.replace(formatting, "")
        val plus = compact.startsWith("+")
        val digits = if (plus) compact.substring(1) else compact
        if (digits.isNotEmpty() && digits.all(Char::isDigit)) {
            return when {
                digits.length in 7..15 -> AddressKind.PHONE // E.164 allows at most 15 digits
                digits.length in 3..6 && !plus -> AddressKind.SHORT_CODE
                else -> AddressKind.OPAQUE
            }
        }
        if (s.any(Char::isLetter) && alphaSender.matches(s)) return AddressKind.ALPHA_SENDER
        return AddressKind.OPAQUE
    }

    /** May be used as an SMS/MMS destination. Emails go by MMS, as upstream already does. */
    fun isSendable(raw: String): Boolean = classify(raw) in sendable

    fun allSendable(addresses: Collection<String>): Boolean = addresses.all(::isSendable)

    /** Worth showing to a person as-is (a number, code, email or sender name). */
    fun isDisplayable(raw: String): Boolean = classify(raw) in displayable

    /** Not a phone number, code, email or sender name: an ID, a URI, or nothing at all. */
    fun isUnrecognized(raw: String): Boolean = !isDisplayable(raw)

    fun anyUnrecognized(addresses: Collection<String>): Boolean = addresses.any(::isUnrecognized)

    fun isPhoneLike(raw: String): Boolean = classify(raw).let { it == AddressKind.PHONE || it == AddressKind.SHORT_CODE }

    /**
     * A description of an address's form that never contains the address: safe to copy
     * into a bug report. Lengths are given; characters are not.
     */
    fun shape(raw: String): String {
        val s = raw.trim()
        return when (classify(s)) {
            AddressKind.EMPTY -> "empty"
            AddressKind.PHONE -> "phone number"
            AddressKind.SHORT_CODE -> "short code"
            AddressKind.EMAIL -> "email address"
            AddressKind.ALPHA_SENDER -> "sender name, ${s.length} chars"
            AddressKind.URI -> s.substringBefore(':').lowercase() + ": URI"
            AddressKind.OPAQUE -> when {
                uuid.matches(s) -> "UUID-shaped, ${s.length} chars"
                s.all(Char::isDigit) -> "digits only, ${s.length} chars"
                hex.matches(s) && s.any(Char::isLetter) -> "hex, ${s.length} chars"
                alnum.matches(s) -> "letters and digits, ${s.length} chars"
                else -> "mixed characters, ${s.length} chars"
            }
        }
    }

    private val sendable = setOf(AddressKind.PHONE, AddressKind.SHORT_CODE, AddressKind.EMAIL)
    private val displayable = sendable + AddressKind.ALPHA_SENDER
}
