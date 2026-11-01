package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi

/** Вузол часової шкали в картці робочого процесу: окреме повідомлення або група, сформована послідовними викликами одного інструменту.*/
@Immutable
internal sealed interface AgentWorkStep {
    val key: String

    data class Single(val message: AgentChatMessageUi) : AgentWorkStep {
        override val key: String = message.id
    }

    data class ToolGroup(
        val toolName: String,
        val calls: List<ToolActivityMessageUi>,
    ) : AgentWorkStep {
        override val key: String = "group-${calls.first().id}"
        val running: Boolean get() = calls.any { it.status == ToolActivityStatusUi.Running }
        val failedCount: Int get() = calls.count { it.status == ToolActivityStatusUi.Failed }
    }
}

/**
 * Агент часто читає кілька файлів безперервно або спостерігає за екраном кілька разів; коли інструменти з однаковою назвою з’являються постійно, вони об’єднуються в один вузол.
 * Часова шкала зберігає лише рівень «що було зроблено», а деталі розширюються за потреби. Записи веб-переглядача містять попередній перегляд і залишаються незалежними.
 */
internal fun List<AgentChatMessageUi>.toWorkSteps(): List<AgentWorkStep> = buildList {
    val run = mutableListOf<ToolActivityMessageUi>()

    fun flushRun() {
        when (run.size) {
            0 -> Unit
            1 -> add(AgentWorkStep.Single(run.single()))
            else -> add(AgentWorkStep.ToolGroup(toolName = run.first().toolName, calls = run.toList()))
        }
        run.clear()
    }

    this@toWorkSteps.forEach { message ->
        val groupable = message is ToolActivityMessageUi && message.toolName !in UNGROUPED_TOOLS
        if (groupable && run.isNotEmpty() && run.first().toolName != (message as ToolActivityMessageUi).toolName) {
            flushRun()
        }
        if (groupable) {
            run += message as ToolActivityMessageUi
        } else {
            flushRun()
            add(AgentWorkStep.Single(message))
        }
    }
    flushRun()
}

private val UNGROUPED_TOOLS = setOf("browser_use", "subagent")

/** Необхідна кількість для згорнутого підсумку робочого процесу.*/
internal data class AgentWorkSummary(
    val thinkingCount: Int,
    val toolCount: Int,
    val failedCount: Int,
)

internal fun List<AgentChatMessageUi>.workSummary(): AgentWorkSummary = AgentWorkSummary(
    thinkingCount = count { it is ThinkingMessageUi },
    toolCount = count { it is ToolActivityMessageUi },
    failedCount = count { it is ToolActivityMessageUi && it.status == ToolActivityStatusUi.Failed },
)

/**
 * Згорнути рядок результатів під рядком. Звичайний результат успіху (наприклад, «Завершено») не займає рядок; вихід терміналу займає останній рядок,
 * Оскільки завершення команди зазвичай знаходиться в кінці; у разі невдачі, повторюваний префікс "відмова" та хвіст коду=, що використовуються в журналі, видаляються.
 */
internal fun ToolActivityMessageUi.outcomeLine(): String? {
    val summary = resultSummary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lines = summary.lines().map(String::trim).filter(String::isNotEmpty)
    val head = lines.firstOrNull() ?: return null
    return when (status) {
        ToolActivityStatusUi.Running -> null
        ToolActivityStatusUi.Failed, ToolActivityStatusUi.Unknown -> {
            val detail = lines.drop(1).lastOrNull { it != TRUNCATION_MARK }
            head.removePrefix(FAILURE_PREFIX)
                .substringBefore(FAILURE_CODE_SEPARATOR)
                .takeIf { it.isNotBlank() && it != FAILURE_LABEL }
                ?: detail
        }
        ToolActivityStatusUi.Success -> {
            val output = lines.drop(1).lastOrNull { it != TRUNCATION_MARK }
            when {
                output != null -> output
                head in TRIVIAL_SUCCESS -> null
                else -> head.removePrefix(SUCCESS_PREFIX).takeIf { it.isNotBlank() }
            }
        }
    }
}

// Відповідає фіксованому формату зведення результатів сторони виконання.
private const val FAILURE_LABEL = "втратитипоразка"
private const val FAILURE_PREFIX = "втратитипоразка · "
private const val FAILURE_CODE_SEPARATOR = " · code="
private const val SUCCESS_PREFIX = "закінченостати · "
private const val TRUNCATION_MARK = "…"
private val TRIVIAL_SUCCESS = setOf("закінченостати", "Виконатидобрезакінченостати")
