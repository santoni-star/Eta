package io.github.mangi.eta.agent.terminal

import android.content.SharedPreferences
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** Конфігурація спільної папки: каталог на стороні Android [sourcePath] відображається в /workspace/mounts/[name] у середовищі Linux.*/
internal data class SharedFolderMount(
    val name: String,
    val sourcePath: String,
)

/**
 * Постійність і перевірка конфігурації спільної папки.
 *
 * Монтування не виконується глобально на Android: кожен сеанс Linux виконує зв’язане монтування на основі поточної конфігурації, коли встановлюється власний простір імен монтування.
 * Тому зміни конфігурації набувають чинності під час наступної команди/наступного сеансу, не залишаючи глобального стану, який потрібно очищати після видалення чи перезапуску.
 * Конфігурація зберігається в localAgent SharedPreferences (JSON), а шлях зчитується синхронно.
 */
internal object SharedFolderMounts {
    const val PREFS_KEY = "linux_shared_mounts"
    const val MAX_MOUNTS = 16
    const val LINUX_MOUNTS_ROOT = "/workspace/mounts"
    const val ANDROID_MOUNTS_ROOT = "/data/local/tmp/eta/mounts"

    private val NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,48}")

    /** Області обмеженого доступу до джерела: системне дерево ключів, сама робоча область (повністю видима в середовищі) та /.*/
    private val FORBIDDEN_ROOTS = listOf(
        "/",
        "/proc",
        "/sys",
        "/dev",
        "/system",
        "/vendor",
        "/apex",
        "/product",
        "/data/local/tmp/eta",
    )

    enum class SourceError {
        INVALID_PATH,
        FORBIDDEN_ROOT,
        DUPLICATE,
    }

    enum class NameError {
        INVALID,
        DUPLICATE,
    }

    fun current(preferences: SharedPreferences? = Prefs.localAgentPreferences()): List<SharedFolderMount> {
        val json = runCatching { preferences?.getString(PREFS_KEY, null) }.getOrNull()
        return decode(json.orEmpty())
    }

    fun save(
        mounts: List<SharedFolderMount>,
        preferences: SharedPreferences? = Prefs.localAgentPreferences(),
    ): Boolean {
        val prefs = preferences ?: return false
        return runCatching { prefs.edit().putString(PREFS_KEY, encode(mounts)).commit() }.getOrDefault(false)
    }

    /** Лексична нормалізація: символічні посилання не аналізуються, а форма шляху, вибрана користувачем, зберігається (/sdcard не переписується).*/
    fun normalizeSourcePath(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("/") || '\n' in trimmed || '\r' in trimmed) return null
        val segments = mutableListOf<String>()
        trimmed.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        if (segments.isEmpty()) return null
        return "/" + segments.joinToString("/")
    }

    fun validateSource(
        sourcePath: String,
        existing: List<SharedFolderMount>,
        extraForbiddenRoots: List<String> = emptyList(),
    ): SourceError? {
        val normalized = normalizeSourcePath(sourcePath) ?: return SourceError.INVALID_PATH
        val forbidden = FORBIDDEN_ROOTS + extraForbiddenRoots.mapNotNull(::normalizeSourcePath)
        if (forbidden.any { normalized == it || normalized.startsWith("$it/") }) {
            return SourceError.FORBIDDEN_ROOT
        }
        if (existing.any { it.sourcePath == normalized }) return SourceError.DUPLICATE
        return null
    }

    fun validateName(name: String, existing: List<SharedFolderMount>): NameError? {
        val trimmed = name.trim()
        if (!NAME_PATTERN.matches(trimmed) || trimmed == "." || trimmed == "..") return NameError.INVALID
        if (existing.any { it.name == trimmed }) return NameError.DUPLICATE
        return null
    }

    /** Вивести ім'я монтування за замовчуванням з базового імені вихідного каталогу; вироджуються на спільний доступ, коли символи, що не належать до ASCII, видаляються та порожні.*/
    fun defaultName(sourcePath: String): String {
        val base = sourcePath.trimEnd('/').substringAfterLast('/').trim()
        val candidate = base.filter { char ->
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '.' || char == '_' || char == '-'
        }.take(48)
        return candidate.ifBlank { "share" }
    }

    fun encode(mounts: List<SharedFolderMount>): String {
        val array = JSONArray()
        mounts.forEach { mount ->
            array.put(
                JSONObject()
                    .put("name", mount.name)
                    .put("source", mount.sourcePath)
            )
        }
        return array.toString()
    }

    /** Якщо декодування не вдається або введення є незаконним, введення буде пропущено; якщо весь запис пошкоджено, буде повернено порожній список.*/
    fun decode(json: String): List<SharedFolderMount> {
        if (json.isBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val mounts = mutableListOf<SharedFolderMount>()
        for (index in 0 until array.length()) {
            val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
            val name = item.optString("name").trim()
            val source = normalizeSourcePath(item.optString("source")) ?: continue
            if (!NAME_PATTERN.matches(name) || name == "." || name == "..") continue
            if (mounts.any { it.name == name || it.sourcePath == source }) continue
            mounts += SharedFolderMount(name = name, sourcePath = source)
        }
        return mounts.take(MAX_MOUNTS)
    }
}
