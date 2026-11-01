package io.github.mangi.eta.agent.device

/** Натомість дозволити повторне відтворення, лише якщо підтверджено, що жест ніколи не надсилався системі.*/
internal object GestureFallbackPolicy {
    fun mayFallbackToRoot(errorCode: String): Boolean =
        errorCode == "GESTURE_NOT_DISPATCHED"
}
