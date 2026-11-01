package io.github.mangi.eta.core

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

internal enum class HookInstallStatus {
    INSTALLED,
    MISSING,
    FAILED,
    SKIPPED
}

internal data class HookInstallEntry(
    val group: String,
    val id: String,
    val description: String,
    val status: HookInstallStatus,
    val detail: String? = null
)

internal data class HookInstallReport(
    val group: String,
    val entries: List<HookInstallEntry>
) {
    val installedCount: Int = entries.count { it.status == HookInstallStatus.INSTALLED }
    val missingCount: Int = entries.count { it.status == HookInstallStatus.MISSING }
    val failedCount: Int = entries.count { it.status == HookInstallStatus.FAILED }
    val skippedCount: Int = entries.count { it.status == HookInstallStatus.SKIPPED }

    fun summary(): String =
        "Hook встановитиупаковказакінченостати: installed=$installedCount, missing=$missingCount, " +
            "failed=$failedCount, skipped=$skippedCount"

    companion object {
        fun combine(group: String, reports: Iterable<HookInstallReport>): HookInstallReport {
            val reportList = reports.toList()
            return HookInstallReport(
                group = group,
                entries = reportList.flatMap { it.entries }
            )
        }
    }
}

internal data class HookInstallation(
    val report: HookInstallReport,
    val handles: List<XposedInterface.HookHandle>
) {
    companion object {
        fun combine(group: String, installations: Iterable<HookInstallation>): HookInstallation {
            val installationList = installations.toList()
            return HookInstallation(
                report = HookInstallReport.combine(group, installationList.map { it.report }),
                handles = installationList.flatMap { it.handles }
            )
        }
    }
}

/** Встановіть облікову книгу з чистим станом для звітності, не зберігаючи об’єктів рамки.*/
internal class HookInstallJournal(private val group: String) {
    private val entries = mutableListOf<HookInstallEntry>()

    fun capture(block: () -> Unit, onFailure: (Exception) -> Unit) {
        try {
            block()
        } catch (exception: Exception) {
            failed(
                id = "install.failed",
                description = "$group Hook Групавстановитиупаковка",
                detail = exception.javaClass.simpleName
            )
            onFailure(exception)
        }
    }

    fun installed(id: String, description: String) {
        record(id, description, HookInstallStatus.INSTALLED)
    }

    fun missing(id: String, description: String, detail: String) {
        record(id, description, HookInstallStatus.MISSING, detail)
    }

    fun failed(id: String, description: String, detail: String) {
        record(id, description, HookInstallStatus.FAILED, detail)
    }

    fun skipped(id: String, description: String, detail: String) {
        record(id, description, HookInstallStatus.SKIPPED, detail)
    }

    fun report(): HookInstallReport = HookInstallReport(group, entries.toList())

    private fun record(
        id: String,
        description: String,
        status: HookInstallStatus,
        detail: String? = null
    ) {
        entries += HookInstallEntry(group, id, description, status, detail)
    }
}

/**
 * Реєстр гаків для одного функціонального домену. Тут обробляються лише реєстрація API та діагностика інсталяції, а цільове позиціонування все ще є відповідальністю функціонального домену.
 */
internal class HookRegistrar(
    private val module: XposedModule,
    logger: ModuleLogger,
    private val group: String
) {
    val logger: ModuleLogger = logger.scoped(group)

    private val journal = HookInstallJournal(group)
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private val registrationKeys = mutableSetOf<RegistrationKey>()

    /**
     * Повне встановлення в межах функціональної групи. Хуки, які були зареєстровані до того, як сталася виняток, усе ще залишатимуться у звіті та збиратимуть обробку.
     */
    fun install(block: HookRegistrar.() -> Unit): HookInstallation {
        journal.capture(block = { block() }) { exception ->
            // XposedFrameworkError є помилкою, і її не буде проковтнути рівень ізоляції функціональної групи.
            logger.error("Hook Групавстановитиупаковкавтратитипоразка", exception)
        }
        return finish()
    }

    fun intercept(
        id: String,
        executable: Executable,
        description: String,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        hooker: (XposedInterface.Chain) -> Any?
    ): XposedInterface.HookHandle? {
        require(STABLE_ID.matches(id)) { "Hook id сіткаРежимнемаєефект: $id" }
        val fullId = "eta.$id"
        val registrationKey = RegistrationKey(executable, fullId)
        if (!registrationKeys.add(registrationKey)) {
            val detail = "Важкаскладні Hook Приміткакнига: $description ($fullId)"
            journal.failed(id, description, detail)
            logger.error(detail)
            return null
        }
        return try {
            val handle = module.hook(executable)
                .setPriority(priority)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(fullId)
                .intercept { chain -> hooker(chain) }
            handles += handle
            journal.installed(id, description)
            logger.debug { "вжевстановитиупаковка Hook: $description" }
            handle
        } catch (exception: Exception) {
            // HookFailedError належить до Error і сюди не входитиме. Його потрібно далі здавати на каркас для обробки.
            registrationKeys.remove(registrationKey)
            journal.failed(id, description, exception.javaClass.simpleName)
            logger.error("встановитиупаковка Hook втратитипоразка: $description", exception)
            null
        }
    }

    fun missing(id: String, description: String, detail: String) {
        require(STABLE_ID.matches(id)) { "Hook id сіткаРежимнемаєефект: $id" }
        journal.missing(id, description, detail)
        logger.warn(detail)
    }

    fun skipped(id: String, description: String, detail: String) {
        require(STABLE_ID.matches(id)) { "Hook id сіткаРежимнемаєефект: $id" }
        journal.skipped(id, description, detail)
        logger.debug { detail }
    }

    private fun finish(): HookInstallation = HookInstallation(
        report = journal.report(),
        handles = handles.toList()
    )

    private companion object {
        val STABLE_ID = Regex("[a-z0-9][a-z0-9._-]*")
    }

    private data class RegistrationKey(
        val executable: Executable,
        val fullId: String
    )
}
