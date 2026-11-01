package io.github.mangi.eta.core

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/** Дросель внутрішнього журналу; використовує монотонний годинник і на нього не впливають налаштування системного часу.*/
internal class LogThrottle(
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis
) {
    private val lastAcceptedAt = ConcurrentHashMap<String, Long>()

    fun shouldLog(key: String, windowMs: Long): Boolean {
        require(key.isNotBlank()) { "деньчестолюбствоФестивальпотік key немаєздатнийдлянульовий" }
        require(windowMs >= 0L) { "деньчестолюбствоФестивальпотіквікноротнемаєздатнийдлятягарномер" }

        val now = uptimeMillis()
        var accepted = false
        lastAcceptedAt.compute(key) { _, previous ->
            if (previous == null || now < previous || now - previous >= windowMs) {
                accepted = true
                now
            } else {
                previous
            }
        }
        return accepted
    }
}
