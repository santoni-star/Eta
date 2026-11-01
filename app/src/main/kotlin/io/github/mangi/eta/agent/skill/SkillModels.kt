package io.github.mangi.eta.agent.skill

import androidx.compose.runtime.Immutable

/**
 * Запис індексу навичок - відповідає метаінформації файлу [SKILL.md] на диску.
 */
@Immutable
data class SkillIndexEntry(
    val id: String,
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val rootPath: String,
    val skillFilePath: String,
    val hasScripts: Boolean,
    val hasReferences: Boolean,
    val hasAssets: Boolean,
    val hasEvals: Boolean,
    val enabled: Boolean = true,
    val source: String = "user",
    val installed: Boolean = true,
)

/**
 * Вирішений контекст навичок — містить шляхи основного та супутникового каталогу SKILL.md.
 */
@Immutable
data class ResolvedSkillContext(
    val skillId: String,
    val frontmatter: Map<String, String>,
    val metadata: Map<String, String> = emptyMap(),
    val bodyMarkdown: String,
    val loadedReferences: List<String> = emptyList(),
    val scriptsDir: String? = null,
    val assetsDir: String? = null,
    val triggerReason: String,
)

@Immutable
data class SkillCompatibilityResult(
    val available: Boolean,
    val reason: String? = null,
)

/**
 * Набір контекстів навичок, аналізованих під час одного запуску агента.
 */
@Immutable
data class SkillContext(
    val installedSkills: List<SkillIndexEntry> = emptyList(),
) {
    companion object {
        val EMPTY = SkillContext()
    }
}

/** Результати аналізу файлу SKILL.md.*/
internal data class ParsedSkillFile(
    val frontmatter: Map<String, String>,
    val body: String,
)
