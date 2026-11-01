package io.github.mangi.eta.agent.skill

import java.io.File

/**
 * Синтаксичний аналізатор SKILL.md - витяг структурованої інформації з YAML frontmatter + Markdown body.
 *
 * Підтримує багаторядкові блоки `>` / `|`, підблоки з відступом і звичайні рядки `key: value`.
 * Чиста обробка рядків, відсутність залежності від зовнішніх бібліотек YAML.
 */
internal object SkillParser {

    /**
     * Читати та аналізувати [skillFile] і повертати карту переднього матеріалу + рядок тіла.
     * Повертає значення null, якщо файл не існує або не є файлом.
     */
    fun parseSkillFile(skillFile: File): ParsedSkillFile? {
        if (!skillFile.exists() || !skillFile.isFile) return null
        val raw = skillFile.readText()
        if (!raw.startsWith("---")) {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
        }
        val markerIndex = raw.indexOf("\n---", startIndex = 3)
        if (markerIndex <= 0) {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
        }
        val frontmatterText = raw.substring(3, markerIndex).trim('\n', '\r')
        val body = raw.substring(markerIndex + 4).trim()
        return ParsedSkillFile(
            frontmatter = parseSimpleFrontmatter(frontmatterText),
            body = body,
        )
    }

    /**
     * Простий розбір переднього матеріалу YAML.
     *
     * підтримка:
     * - `key: value` один рядок
     * - `клавіша: >` Складіть багаторядкові блоки
     * - `key: |` літеральний багаторядковий блок
     * - `key:`, за яким йде підблок із відступом
     */
    fun parseSimpleFrontmatter(frontmatter: String): Map<String, String> {
        if (frontmatter.isBlank()) return emptyMap()
        val lines = frontmatter.lines()
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            if (rawLine.isBlank()) {
                index += 1
                continue
            }
            val keyMatch = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(rawLine)
            if (keyMatch == null) {
                index += 1
                continue
            }
            val key = keyMatch.groupValues[1]
            val value = keyMatch.groupValues[2]
            if (YAML_BLOCK_SCALAR.matches(value)) {
                val literal = value.startsWith('|')
                val blockLines = mutableListOf<String>()
                index += 1
                while (index < lines.size && (lines[index].startsWith("  ") || lines[index].isBlank())) {
                    val next = lines[index]
                    blockLines += if (next.isBlank()) "" else next.trim()
                    index += 1
                }
                result[key] = if (literal) {
                    blockLines.joinToString("\n").trim()
                } else {
                    foldYamlLines(blockLines)
                }
                continue
            }
            if (value.isBlank()) {
                val builder = StringBuilder()
                index += 1
                while (index < lines.size && (lines[index].startsWith("  ") || lines[index].startsWith("\t"))) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(lines[index].trimEnd())
                    index += 1
                }
                result[key] = builder.toString().trim()
                continue
            }
            result[key] = unquoteScalar(value)
            index += 1
        }
        return result
    }

    /** Підтримка загальних одинарних і подвійних скалярів у лапках YAML; складне екранування все ще залишається за авторами Skill, щоб уникати використання.*/
    private fun unquoteScalar(raw: String): String {
        val value = raw.trim()
        if (value.length < 2) return value
        val quoted = (value.first() == '"' && value.last() == '"') ||
            (value.first() == '\'' && value.last() == '\'')
        return if (quoted) value.substring(1, value.lastIndex) else value
    }

    /** `>` згортає абзаци, утворені розривами рядків, і зберігає порожні рядки; трейлінг chomp не має семантичної різниці в метаданих.*/
    private fun foldYamlLines(lines: List<String>): String = buildString {
        var pendingBlankLines = 0
        lines.forEach { line ->
            if (line.isBlank()) {
                pendingBlankLines += 1
            } else {
                if (isNotEmpty()) {
                    if (pendingBlankLines == 0) append(' ')
                    else repeat(pendingBlankLines + 1) { append('\n') }
                }
                append(line)
                pendingBlankLines = 0
            }
        }
    }.trim()

    /**
     * Аналізуйте підблоки з відступами на карти ключ-значення (для полів метаданих).
     */
    fun parseIndentedBlock(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().mapNotNull { line ->
            val match = Regex("^\\s*([A-Za-z0-9_.-]+):\\s*(.*)$").find(line) ?: return@mapNotNull null
            match.groupValues[1] to match.groupValues[2].trim().trim('"')
        }.toMap()
    }

    /**
     * Створіть нормалізований ідентифікатор навички з назви каталогу та назви переднього матеріалу.
     */
    fun sanitizeSkillId(directoryName: String, frontmatterName: String?): String {
        val candidate = frontmatterName?.trim().takeUnless { it.isNullOrBlank() } ?: directoryName
        return candidate.lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { directoryName.lowercase() }
    }

    /**
     * Нормалізований рядок пошуку - для відповідності ідентифікатора/імені/шляху.
     */
    fun normalizeSkillLookup(value: String): String =
        value.trim()
            .lowercase()
            .replace('\\', '/')
            .removeSuffix("/skill.md")
            .removeSuffix("/")
            .replace(Regex("\\s+"), "")
            .replace("-", "")
            .replace("_", "")

    private val YAML_BLOCK_SCALAR = Regex("[>|][+-]?")

}
