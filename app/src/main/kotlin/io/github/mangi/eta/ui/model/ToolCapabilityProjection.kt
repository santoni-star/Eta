package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.LocalToolRequirement
import io.github.mangi.eta.agent.tool.RootRequirement

/** Кілька довідкових карток у браузері показують різні операції одного інструменту, і вимоги все ще випливають із фактичного виконання інструменту.*/
internal fun toolCardRequirement(id: String): LocalToolRequirement {
    val toolName = actualToolName(id)
    return requireNotNull(AgentToolRequirements.find(toolName)) { "Unknown tool card: $id" }
}

internal fun visibleOnCurrentDevice(id: String, rootGranted: Boolean, colorOs: Boolean): Boolean {
    val requirement = toolCardRequirement(id)
    return (rootGranted || requirement.rootRequirement != RootRequirement.REQUIRED) &&
        (colorOs || !requirement.colorOs)
}

internal fun actualToolName(id: String): String = when (id) {
    "browser_read", "browser_interact", "browser_screenshot" -> "browser_use"
    else -> id
}

internal fun projectToolGroups(groups: List<ToolGroupUi>, showAll: Boolean, rootGranted: Boolean, colorOs: Boolean): List<ToolGroupUi> =
    groups.map { group ->
        group.copy(tools = group.tools.filter { showAll || visibleOnCurrentDevice(it.id, rootGranted, colorOs) })
    }.filter { it.tools.isNotEmpty() }

/** За відсутності звичайних дозволів спочатку буде надано виконуваний запис авторизації, і під час перегляду інструкцій щодо вдосконалення не буде запитуватися root.*/
internal fun toolCardAction(id: String, capabilities: AgentToolCapabilities): AgentToolsAction? {
    val requirement = toolCardRequirement(id)
    return when (capabilities.unavailableCode(actualToolName(id))) {
        "ROOT_REQUIRED", "DEVICE_UNSUPPORTED" -> AgentToolsAction.OpenEnhancements
        null -> when {
            id == "web_search" || id == "fetch_url" -> AgentToolsAction.OpenWebToolsSettings
            id.startsWith("browser_") -> AgentToolsAction.OpenBrowser
            !capabilities.rootAvailable && requirement.rootRequirement == RootRequirement.PARTIAL ->
                AgentToolsAction.OpenEnhancements
            else -> null
        }
        else -> AgentToolsAction.OpenPermissions
    }
}
