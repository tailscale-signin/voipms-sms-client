package net.kourlas.voipms_sms.privacy

import kotlin.math.abs

/** An intentional pull beyond the top of Messages. Short pulls remain available for refresh. */
internal class PrivateInboxGesture(private val threshold: Float) {
    private var startX = 0f
    private var startY = 0f
    private var watching = false
    fun start(x: Float, y: Float, atMessagesTop: Boolean) {
        startX = x
        startY = y
        watching = atMessagesTop
    }

    fun move(x: Float, y: Float): Boolean {
        if (!watching) return false
        val down = y - startY
        val horizontal = abs(x - startX)
        if (down < -threshold / 8 || horizontal > threshold / 5 && horizontal > down) watching = false
        if (!watching || down < threshold || down < horizontal * 2) return false
        watching = false
        return true
    }

    fun cancel() { watching = false }
}
