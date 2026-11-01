package io.github.mangi.eta.agent.accessibility

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * Підтвердьте, що службу доступності Eta дійсно підключено, перш ніж запускати інструмент GUI.
 *
 * Постійний захист, запис безпечних налаштувань, від’єднання та повторне прив’язування – усе це обробляється серверною частиною system_server. Не звертайтеся сюди
 * Root, ані напряму змінювати налаштування системи; не закривається, коли захист вимкнено або серверна частина недоступна.
 */
object AgentAccessibilityKeeper {
    internal fun ensureEnabledForGuiOperation(context: Context): AccessibilityEnableResult {
        val startedAt = SystemClock.elapsedRealtime()
        val result = ensureAvailable(
            serviceAvailable = AgentAccessibilityService::isAvailable,
            protectionEnabled = { AccessibilityProtectionClient.isEnabled(context) },
            requestRecovery = {
                AccessibilityProtectionClient.requestRecoveryBlocking(context) ==
                    AccessibilityProtectionClient.ControlStatus.APPLIED
            },
            awaitServiceBinding = ::awaitServiceBinding,
            protectionAvailable = { EtaApp.serviceInstance != null },
        )
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (result.available) {
            AndroidAgentLogger.info(
                "Agent accessibility action=ensure_for_gui outcome=completed " +
                    "recoveryRequested=${result.recoveryRequested} " +
                    "elapsed_ms=$elapsedMs"
            )
        } else {
            AndroidAgentLogger.warn(
                "Agent accessibility action=ensure_for_gui outcome=failed " +
                    "code=${result.code} recoveryRequested=${result.recoveryRequested} " +
                    "elapsed_ms=$elapsedMs"
            )
        }
        return result
    }

    internal fun ensureAvailable(
        serviceAvailable: () -> Boolean,
        protectionEnabled: () -> Boolean,
        requestRecovery: () -> Boolean,
        awaitServiceBinding: () -> Boolean,
        protectionAvailable: () -> Boolean = { true },
    ): AccessibilityEnableResult {
        if (serviceAvailable()) {
            return AccessibilityEnableResult.available(recoveryRequested = false)
        }
        if (!protectionAvailable() || !protectionEnabled()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_UNAVAILABLE",
                message = "Eta немаєбар'єрперешкоджатиОдягобслуговуванняще нінавітьзловити；будь ласкаіснуютьКраваткасистеманалаштувативстановитисерединаВІДЧИНЕНОпочаток Eta немаєбар'єрперешкоджатиОдягобслуговування",
                recoveryRequested = false,
            )
        }
        if (!requestRecovery()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_PROTECTION_UNAVAILABLE",
                message = "немаєбар'єрперешкоджатизберегтизахиститиназадкінецьнемаєможевикористовувати；книгаДругосортний GUI Тримайтеробитище ніВиконатидобре",
                recoveryRequested = true,
            )
        }
        if (!awaitServiceBinding()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_REPAIR_TIMEOUT",
                message = "Eta немаєбар'єрперешкоджатиОдягобслуговуванняще нііснуютьвідновитискладнігодинаобмеженняВсерединінавітьзловити；книгаДругосортний GUI Тримайтеробитище ніВиконатидобре",
                recoveryRequested = true,
            )
        }
        return AccessibilityEnableResult.available(recoveryRequested = true)
    }

    private fun awaitServiceBinding(): Boolean {
        repeat(SERVICE_BIND_ATTEMPTS) {
            if (AgentAccessibilityService.isAvailable()) return true
            SystemClock.sleep(SERVICE_BIND_POLL_MS)
        }
        return AgentAccessibilityService.isAvailable()
    }

    private const val SERVICE_BIND_ATTEMPTS = 60
    private const val SERVICE_BIND_POLL_MS = 100L
}

internal data class AccessibilityEnableResult(
    val available: Boolean,
    val code: String = "",
    val message: String = "",
    val recoveryRequested: Boolean,
) {
    companion object {
        fun available(
            recoveryRequested: Boolean,
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = true,
            recoveryRequested = recoveryRequested,
        )

        fun failure(
            code: String,
            message: String,
            recoveryRequested: Boolean,
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = false,
            code = code,
            message = message,
            recoveryRequested = recoveryRequested,
        )
    }
}
