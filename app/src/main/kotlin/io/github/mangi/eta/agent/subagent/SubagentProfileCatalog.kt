package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.skill.SkillParser
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import java.io.File
import java.nio.file.Files

/**
 * Керуйте вбудованими та спеціальними профілями субагентів.
 *
 * Настроюваний субагент зберігається в `agents/<name>.md` у робочій області за замовчуванням, використовуючи той самий формат, що й код Kimi
 * Текстовий формат YAML frontmatter + Markdown:
 * ```markdown
 * ---
 * name: bill-analyzer
 * опис: спеціалізується на отриманні та узагальненні персональних рахунків і замовлень
 * whenToUse: використовується, коли потрібно порахувати рахунки, перевірити замовлення або проаналізувати споживання
 * tools: summarize_bills, search_bills, search_personal_orders, read_personal_item
 * ---
 * Ви субагент, який спеціалізується на виставленні рахунків та аналізі споживання...
 * ```
 */
internal object SubagentProfileCatalog {
    private val NAME_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private const val MAX_PROFILE_FILE_BYTES = 64 * 1024L

    fun defaultAgentsDir(): File = File(TerminalRuntime.userWorkspacePath, "agents")

    fun isValidName(name: String): Boolean =
        name.length in 2..48 && NAME_REGEX.matches(name)

    fun listAll(agentsDir: File = defaultAgentsDir()): List<SubagentProfile> {
        val merged = linkedMapOf<String, SubagentProfile>()
        SubagentProfile.BUILTIN_PROFILES.forEach { profile ->
            merged[profile.name] = profile
        }
        discoverCustomProfiles(agentsDir).forEach { custom ->
            val existing = merged[custom.name]
            if (existing != null && existing.builtin && !custom.override) {
                return@forEach
            }
            merged[custom.name] = custom
        }
        return merged.values.toList()
    }

    fun listCustom(agentsDir: File = defaultAgentsDir()): List<SubagentProfile> =
        discoverCustomProfiles(agentsDir)

    fun get(name: String, agentsDir: File = defaultAgentsDir()): SubagentProfile? {
        val normalized = name.trim().lowercase()
        if (normalized.isEmpty()) return null
        return listAll(agentsDir).firstOrNull { it.name == normalized }
    }

    fun saveCustomProfile(
        name: String,
        description: String,
        prompt: String,
        whenToUse: String = "",
        tools: List<String> = emptyList(),
        disallowedTools: List<String> = emptyList(),
        overrideBuiltin: Boolean = false,
        agentsDir: File = defaultAgentsDir(),
    ): SubagentProfile {
        val normalizedName = name.trim().lowercase()
        require(isValidName(normalizedName)) {
            "Subagent назвасказатиповиненОбов'язководля 2~48 бітМаленькийНапишітьхарактермати、номерхарактерінавітьхарактерсимвол（kebab-case，прикладяк bill-analyzer）"
        }
        if (normalizedName in SubagentProfile.BUILTIN_NAMES && !overrideBuiltin) {
            throw IllegalArgumentException(
                "немаєздатнийкришкабудуватиВсерединівстановити Subagent \"$normalizedName\"；будь ласкаЗмінавикористовуватищовінназвасказати（прикладяк custom-$normalizedName）абошоуРежимВІДЧИНЕНОпочаток override",
            )
        }
        val cleanDescription = description.trim().replace(Regex("\\s+"), " ")
        require(cleanDescription.isNotEmpty()) { "description немаєздатнийдлянульовий" }
        val cleanPrompt = prompt.trim()
        require(cleanPrompt.isNotEmpty()) { "prompt немаєздатнийдлянульовий" }

        val cleanWhenToUse = whenToUse.trim().replace(Regex("\\s+"), " ")
        val cleanTools = tools.map { it.trim() }
            .filter { it.isNotEmpty() && it !in SubagentProfile.FORBIDDEN_TOOLS }
            .distinct()
        val cleanDisallowed = disallowedTools.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (!agentsDir.exists() && !agentsDir.mkdirs()) {
            error("немаєЗаконстворитивстановити agents Головазапис：${agentsDir.absolutePath}")
        }
        val canonicalRoot = agentsDir.canonicalFile.toPath()
        val targetFile = File(agentsDir, "$normalizedName.md")
        val canonicalTarget = targetFile.canonicalFile.toPath()
        require(canonicalTarget.startsWith(canonicalRoot) && canonicalTarget != canonicalRoot) {
            "немаєЗаконз Subagent мистецтваштукдорогашлях"
        }
        require(!Files.isSymbolicLink(targetFile.toPath())) {
            "відхилитиАбсолютноНапишітьвведітьсимволНомерланцюгзловитимистецтваштук"
        }

        val markdown = renderProfileMarkdown(
            name = normalizedName,
            description = cleanDescription,
            whenToUse = cleanWhenToUse,
            tools = cleanTools,
            disallowedTools = cleanDisallowed,
            overrideBuiltin = overrideBuiltin,
            prompt = cleanPrompt,
        )
        targetFile.writeText(markdown, Charsets.UTF_8)
        return parseProfileFile(targetFile)
            ?: error("зберегтижити Subagent мистецтваштукназадрозв'язатианалізувативтратитипоразка：$normalizedName")
    }

    fun deleteCustomProfile(
        name: String,
        agentsDir: File = defaultAgentsDir(),
    ): Boolean {
        val normalizedName = name.trim().lowercase()
        if (!isValidName(normalizedName) || !agentsDir.exists()) return false
        val canonicalRoot = agentsDir.canonicalFile.toPath()
        val targetFile = File(agentsDir, "$normalizedName.md")
        if (!targetFile.exists() || Files.isSymbolicLink(targetFile.toPath())) return false
        val canonicalTarget = targetFile.canonicalFile.toPath()
        if (!canonicalTarget.startsWith(canonicalRoot) || canonicalTarget == canonicalRoot) return false
        return targetFile.delete()
    }

    private fun discoverCustomProfiles(agentsDir: File): List<SubagentProfile> {
        if (!agentsDir.exists() || !agentsDir.isDirectory) return emptyList()
        if (Files.isSymbolicLink(agentsDir.toPath())) return emptyList()
        val canonicalRoot = runCatching { agentsDir.canonicalFile.toPath() }.getOrNull() ?: return emptyList()
        return agentsDir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file ->
                file.isFile &&
                    !file.name.startsWith(".") &&
                    file.name.endsWith(".md") &&
                    file.length() in 1..MAX_PROFILE_FILE_BYTES &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    runCatching { file.canonicalFile.toPath().startsWith(canonicalRoot) }.getOrDefault(false)
            }
            .mapNotNull(::parseProfileFile)
            .distinctBy { it.name }
            .sortedBy { it.name }
            .toList()
    }

    internal fun parseProfileFile(file: File): SubagentProfile? {
        val parsed = SkillParser.parseSkillFile(file) ?: return null
        val frontmatter = parsed.frontmatter
        val rawName = frontmatter["name"]?.trim()?.lowercase()
            .takeUnless { it.isNullOrBlank() }
            ?: file.nameWithoutExtension.trim().lowercase()
        if (!isValidName(rawName)) return null

        val description = frontmatter["description"]?.trim().orEmpty()
        if (description.isEmpty()) return null
        val body = parsed.body.trim()
        if (body.isEmpty()) return null

        val whenToUse = (frontmatter["whenToUse"] ?: frontmatter["when_to_use"])?.trim()
            .takeUnless { it.isNullOrBlank() }
            ?: description
        val override = frontmatter["override"]?.trim()?.equals("true", ignoreCase = true) == true
        val rawTools = parseListField(frontmatter["tools"])
        val rawDisallowed = parseListField(frontmatter["disallowedTools"] ?: frontmatter["disallowed_tools"])
        val explicitTools = when {
            rawTools == null -> setOf("*")
            rawTools.isEmpty() -> setOf("*")
            else -> rawTools.filterNot { it in SubagentProfile.FORBIDDEN_TOOLS }.toSet()
        }
        val highlights = when {
            rawTools == null || rawTools.isEmpty() || "*" in rawTools -> listOf("read_file", "glob_files", "grep_files", "web_search", "personal_data", "terminal")
            else -> rawTools.filterNot { it in SubagentProfile.FORBIDDEN_TOOLS }.take(8)
        }

        return SubagentProfile(
            name = rawName,
            summary = description,
            whenToUse = whenToUse,
            toolHighlights = highlights,
            roleInstruction = body,
            allowWriteFilesAndTerminal = true,
            builtin = false,
            override = override,
            explicitTools = explicitTools,
            disallowedTools = rawDisallowed.orEmpty().toSet(),
            filePath = file.absolutePath,
        )
    }

    private fun parseListField(raw: String?): List<String>? {
        if (raw == null) return null
        val trimmed = raw.trim().removeSurrounding("[", "]")
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.lines()
            .flatMap { line ->
                val cleaned = line.trim().removePrefix("-").trim()
                cleaned.split(",")
            }
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
    }

    private fun renderProfileMarkdown(
        name: String,
        description: String,
        whenToUse: String,
        tools: List<String>,
        disallowedTools: List<String>,
        overrideBuiltin: Boolean,
        prompt: String,
    ): String = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("description: ${quoteYamlIfNeeded(description)}")
        if (whenToUse.isNotEmpty() && whenToUse != description) {
            appendLine("whenToUse: ${quoteYamlIfNeeded(whenToUse)}")
        }
        if (overrideBuiltin) {
            appendLine("override: true")
        }
        if (tools.isNotEmpty()) {
            appendLine("tools: ${tools.joinToString(", ")}")
        }
        if (disallowedTools.isNotEmpty()) {
            appendLine("disallowedTools: ${disallowedTools.joinToString(", ")}")
        }
        appendLine("---")
        appendLine()
        appendLine(prompt)
    }

    private fun quoteYamlIfNeeded(value: String): String {
        val singleLine = value.replace("\n", " ").trim()
        return if (singleLine.any { it in ":[]{},#&*!|>'\"%@`" }) {
            "\"${singleLine.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } else {
            singleLine
        }
    }
}
