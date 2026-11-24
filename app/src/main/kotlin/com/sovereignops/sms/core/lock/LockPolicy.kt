package com.sovereignops.sms.core.lock

/**
 * When the app lock asks again after the app was left. The lock itself is upstream
 * Fossify's (PIN, pattern or biometrics, set in Settings → Password protection); this only
 * chooses its timeout and whether turning the screen off locks it.
 */
enum class LockTimeout(val label: String, private val millis: Long?) {
    IMMEDIATE("Immediately", 0L),
    ONE_MINUTE("After 1 minute", 60_000L),
    FIVE_MINUTES("After 5 minutes", 300_000L),
    SCREEN_OFF("When the screen turns off", null),
    ;

    /** Value for the upstream lock's unlock timeout. Screen-off mode never times out by itself. */
    val upstreamTimeoutMs: Long get() = millis ?: SCREEN_OFF_HOLD_MS

    /** Immediate and Screen-off lock as the screen turns off; timed modes wait out their timeout. */
    val locksOnScreenOff: Boolean get() = this == IMMEDIATE || this == SCREEN_OFF

    companion object {
        /** Long enough that only the screen turning off (or a restart) locks the app. */
        const val SCREEN_OFF_HOLD_MS = 24L * 60 * 60 * 1000

        fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: IMMEDIATE
    }
}
