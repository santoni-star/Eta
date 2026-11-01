package io.github.mangi.eta.agent.runtime

import android.os.SystemClock
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.accessibility.PackageWindowVisibility
import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ізолюйте зовнішній вхід від реального робочого об’єкта зовнішнього інструменту.
 *
 * Дія закриття, підтвердження стабільності вікна та перший знімок екрана виключають спільний доступ до того самого опису запису, запобігаючи кожному шару від вгадування статусу запису окремо.
 */
internal class EntrySurfaceGuard private constructor(
    internal val targetPackageName: String?,
    private val logger: AgentLogger,
    private val ownedSurfaceDismissal: (() -> Boolean)?,
) {
    private val triggered = AtomicBoolean(false)
    private val dismissalCompleted = AtomicBoolean(false)
    // Вікна спеціальних можливостей можуть зникнути перед виходом із Surface; має бути використано першим знімком екрана після закриття та не може бути очищено після підтвердження закриття.
    private val screenshotExclusionPending = AtomicBoolean(targetPackageName != null)

    val wasTriggered: Boolean
        get() = triggered.get()

    fun dismissOnce(): Boolean {
        if (dismissalCompleted.get()) return true
        if (!triggered.compareAndSet(false, true)) return dismissalCompleted.get()
        ownedSurfaceDismissal?.let { dismiss ->
            val startedAt = System.nanoTime()
            val completed = runCatching(dismiss).getOrDefault(false)
            val waitedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
            if (completed) {
                dismissalCompleted.set(true)
                logger.debug {
                    "Agent runtime owned entry surface dismissed before foreground operation " +
                        "waitedMs=$waitedMillis"
                }
            } else {
                // Закриття власного під'їзду є ідемпотентною спрямованою операцією. Після помилки підтвердження знову дозволено, і базова програма не буде випадково закрита.
                triggered.set(false)
                logger.warn(
                    "Agent runtime owned entry surface dismiss incomplete before foreground " +
                        "operation: waitedMs=$waitedMillis"
                )
            }
            return completed
        }

        val service = AgentAccessibilityService.current()
        if (service == null) {
            triggered.set(false)
            logger.warn("Agent runtime entry surface dismiss skipped: accessibility service unavailable")
            return false
        }

        val packageName = targetPackageName
        val visibility = packageName?.let(service::packageWindowVisibility)
        when (EntrySurfaceDismissPolicy.decide(packageName, visibility)) {
            EntrySurfaceDismissPolicy.Decision.ALREADY_GONE -> {
                if (!service.awaitPackageWindowGone(packageName!!)) {
                    triggered.set(false)
                    logger.warn(
                        "Agent runtime entry surface absence was not stable; keep screenshot " +
                            "exclusion and retry later: package=$packageName",
                    )
                    return false
                }
                dismissalCompleted.set(true)
                logger.debug {
                    "Agent runtime entry surface already gone before foreground operation " +
                        "package=$packageName"
                }
                return true
            }
            EntrySurfaceDismissPolicy.Decision.DEFER -> {
                triggered.set(false)
                logger.warn(
                    "Agent runtime entry surface visibility unknown; keep screenshot exclusion " +
                        "and retry later: package=$packageName",
                )
                return false
            }
            EntrySurfaceDismissPolicy.Decision.SEND_BACK -> Unit
        }
        val startedAt = SystemClock.elapsedRealtime()
        val actionResult = service.globalActionResult("BACK")
        val windowGone = packageName?.let(service::awaitPackageWindowGone) ?: actionResult.ok
        val waitedMillis = SystemClock.elapsedRealtime() - startedAt
        val completed = if (packageName == null) actionResult.ok else windowGone

        if (completed) {
            dismissalCompleted.set(true)
            logger.debug {
                "Agent runtime entry surface dismissed before foreground operation " +
                    "package=$packageName visibilityBefore=$visibility waitedMs=$waitedMillis"
            }
        } else {
            logger.warn(
                "Agent runtime entry surface dismiss incomplete before foreground operation: " +
                    "actionCode=${actionResult.code} windowGone=$windowGone package=$packageName " +
                    "visibilityBefore=$visibility waitedMs=$waitedMillis"
            )
        }
        return completed
    }

    fun consumeScreenshotExcludedPackages(): Set<String> {
        val packageName = targetPackageName ?: return emptySet()
        return if (screenshotExclusionPending.compareAndSet(true, false)) {
            setOf(packageName)
        } else {
            emptySet()
        }
    }

    companion object {
        fun from(
            handoff: AgentRuntimeWire.EntryHandoff?,
            logger: AgentLogger,
            etaVoiceSurfaceDismissal: (() -> Boolean)? = null,
        ): EntrySurfaceGuard? {
            if (handoff?.dismissEntrySurfaceOnForegroundOperation != true) return null
            val packageName = when (handoff.source) {
                BREENO_HANDOFF_SOURCE -> BREENO_PACKAGE_NAME
                XIAOAI_HANDOFF_SOURCE -> XIAOAI_PACKAGE_NAME
                AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE -> ETA_PACKAGE_NAME
                else -> null
            }
            val ownedSurfaceDismissal = etaVoiceSurfaceDismissal.takeIf {
                handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
            }
            return EntrySurfaceGuard(packageName, logger, ownedSurfaceDismissal)
        }

        private const val BREENO_HANDOFF_SOURCE = "breeno"
        private const val BREENO_PACKAGE_NAME = "com.heytap.speechassist"
        private const val XIAOAI_HANDOFF_SOURCE = "xiaoai"
        private const val XIAOAI_PACKAGE_NAME = "com.miui.voiceassist"
        private const val ETA_PACKAGE_NAME = "io.github.mangi.eta"
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal object EntrySurfaceDismissPolicy {
    enum class Decision {
        ALREADY_GONE,
        SEND_BACK,
        DEFER,
    }

    fun decide(
        targetPackageName: String?,
        visibility: PackageWindowVisibility?,
    ): Decision = when {
        targetPackageName == null -> Decision.SEND_BACK
        visibility == PackageWindowVisibility.GONE -> Decision.ALREADY_GONE
        visibility == PackageWindowVisibility.VISIBLE -> Decision.SEND_BACK
        else -> Decision.DEFER
    }
}
