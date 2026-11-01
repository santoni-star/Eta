package io.github.mangi.eta.agent.skill

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class SkillRecoveryRecord(
    val id: String,
    val originalTargetExisted: Boolean,
    val backupCompleted: Boolean = false,
    val newTargetCommitted: Boolean = false,
    val registrySnapshot: SkillRegistryRecoverySnapshot = SkillRegistryRecoverySnapshot(
        skillId = id,
        entryExisted = false,
    ),
)

internal data class SkillRegistryRecoverySnapshot(
    val skillId: String,
    val entryExisted: Boolean,
    val enabled: Boolean = false,
    val source: String = "",
    val installState: String = "",
)

internal data class RecoveredSkillOperation(
    val operationDirectory: File,
    val records: List<SkillRecoveryRecord>,
)

internal class PendingSkillRecoveryJournal private constructor(
    private val operationDirectory: File,
    records: List<SkillRecoveryRecord>,
) {
    private var records = records

    fun markBackupCompleted(skillId: String) {
        update(skillId) { it.copy(backupCompleted = true) }
    }

    fun markNewTargetCommitted(skillId: String) {
        update(skillId) { it.copy(newTargetCommitted = true) }
    }

    fun clear() {
        Files.deleteIfExists(File(operationDirectory, JOURNAL_FILE_NAME).toPath())
    }

    private fun update(skillId: String, transform: (SkillRecoveryRecord) -> SkillRecoveryRecord) {
        var found = false
        records = records.map { record ->
            if (record.id == skillId) {
                found = true
                transform(record)
            } else {
                record
            }
        }
        check(found) { "відновитискладніденьчестолюбствосерединанемаєжитиіснують Skill：$skillId" }
        writeJournalAtomically(operationDirectory, records)
    }

    companion object {
        fun begin(
            skillsRoot: File,
            operationDirectory: File,
            records: List<SkillRecoveryRecord>,
        ): PendingSkillRecoveryJournal {
            validateOperationDirectory(skillsRoot, operationDirectory)
            require(records.isNotEmpty()) { "відновитискладніденьчестолюбстводомалопотребахочутьодиніндивідуальний Skill" }
            validateRecords(records)
            writeJournalAtomically(operationDirectory, records)
            return PendingSkillRecoveryJournal(operationDirectory, records)
        }
    }
}

internal class SkillRecoveryRequiredException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Необхідно викликати під час утримання міжпроцесного блокування [SkillMutationLock].*/
internal fun recoverPendingSkillOperations(
    skillsRoot: File,
    directoryMover: SkillDirectoryMover = AtomicSkillDirectoryMover,
): List<RecoveredSkillOperation> {
    val workRoot = skillInstallerWorkRoot(skillsRoot)
    if (!Files.exists(workRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
    if (!workRoot.isDirectory || Files.isSymbolicLink(workRoot.toPath())) {
        throw SkillRecoveryRequiredException("Skill відновитискладніГоловазаписнемаєвстановитиПовний")
    }
    val operations = workRoot.listFiles()
        ?.filter { it.name.startsWith(OPERATION_PREFIX) }
        ?.sortedBy { it.name }
        ?: throw SkillRecoveryRequiredException("немаєЗакончитатиВиберіть Skill відновитискладніГоловазапис")
    val recovered = operations.mapNotNull { operation ->
        val journalFile = File(operation, JOURNAL_FILE_NAME)
        if (!Files.exists(journalFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
        recoverOperation(skillsRoot, operation, journalFile, directoryMover)
    }
    val duplicateIds = recovered
        .flatMap { it.records }
        .groupBy { it.id }
        .filterValues { it.size > 1 }
        .keys
    if (duplicateIds.isNotEmpty()) {
        recoveryFailure("багатоіндивідуальнийлікувативідновитискладнірічобслуговуванняСумкаМіститьВзаємноте саме Skill")
    }
    return recovered
}

private fun recoverOperation(
    skillsRoot: File,
    operation: File,
    journalFile: File,
    directoryMover: SkillDirectoryMover,
): RecoveredSkillOperation {
    try {
        validateOperationDirectory(skillsRoot, operation)
        if (Files.isSymbolicLink(journalFile.toPath()) || !journalFile.isFile) {
            recoveryFailure("Skill відновитискладніденьчестолюбствонемаєтакзагальнийПасмистецтваштук")
        }
        if (journalFile.length() !in 1..MAX_JOURNAL_BYTES) {
            recoveryFailure("Skill відновитискладніденьчестолюбствовеликийМаленькийнемаєефект")
        }
        val records = parseJournal(journalFile)
        val backupRoot = File(operation, BACKUP_DIRECTORY_NAME)
        records.forEach { record ->
            val target = File(skillsRoot, record.id)
            val backup = File(backupRoot, record.id)
            val backupExists = Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)
            if (record.originalTargetExisted) {
                when {
                    backupExists -> restoreBackup(
                        skillsRoot,
                        operation,
                        backupRoot,
                        record.id,
                        directoryMover,
                    )
                    record.backupCompleted -> recoveryFailure("старий Skill Приготуйтечасткавідсутністьвтратити：${record.id}")
                    !isSafeExistingTarget(skillsRoot, target) ->
                        recoveryFailure("старий Skill Головазнаківідновитискладніденьчестолюбствонемаєодиндо：${record.id}")
                }
            } else {
                if (backupExists) recoveryFailure("Повнийновий Skill немаєвідповідьжитиіснуютьстарийПриготуйтечастка：${record.id}")
                if (!deleteSkillPathWithoutFollowingLinks(skillsRoot, target)) {
                    recoveryFailure("немаєЗаконзмінавидалитище нізакінченостативстановитиупаковказ Skill：${record.id}")
                }
            }
        }
        return RecoveredSkillOperation(operationDirectory = operation, records = records)
    } catch (error: SkillRecoveryRequiredException) {
        throw error
    } catch (error: Exception) {
        throw SkillRecoveryRequiredException("Skill оскількирухатисявідновитискладнівтратитипоразка", error)
    }
}

internal fun completeRecoveredSkillOperations(
    skillsRoot: File,
    recovered: List<RecoveredSkillOperation>,
) {
    val workRoot = skillInstallerWorkRoot(skillsRoot)
    recovered.forEach { recovery ->
        val operation = recovery.operationDirectory
        validateOperationDirectory(skillsRoot, operation)
        Files.deleteIfExists(File(operation, JOURNAL_FILE_NAME).toPath())
        // Видалення журналу означає, що файл і реєстр було відновлено разом; тимчасового каталогу журналу не залишилося.
        // Це більше не впливає на індекс, і невдале очищення не може повторно позначити завершене відновлення як очікуване.
        deleteSkillPathWithoutFollowingLinks(workRoot, operation)
    }
}

internal fun createSkillRecoveryOperationDirectory(skillsRoot: File): File {
    val workRoot = prepareSkillInstallerWorkRoot(skillsRoot)
    repeat(8) {
        val operation = File(workRoot, "$OPERATION_PREFIX${UUID.randomUUID()}")
        if (operation.mkdir()) return operation
    }
    throw IOException("немаєЗаконстворитивстановити Skill встановитиупаковкарічобслуговування")
}

private fun restoreBackup(
    skillsRoot: File,
    operation: File,
    backupRoot: File,
    skillId: String,
    directoryMover: SkillDirectoryMover,
) {
    if (
        Files.isSymbolicLink(backupRoot.toPath()) || !backupRoot.isDirectory ||
        !isStrictChild(operation, backupRoot)
    ) {
        recoveryFailure("Skill ПриготуйтечасткакоріньГоловазаписнемаєвстановитиПовний")
    }
    val backup = File(backupRoot, skillId)
    if (
        Files.isSymbolicLink(backup.toPath()) || !backup.isDirectory ||
        !isStrictChild(backupRoot, backup)
    ) {
        recoveryFailure("старий Skill ПриготуйтечастканемаєвстановитиПовний：$skillId")
    }
    val recoveryRoot = File(operation, RECOVERY_DIRECTORY_NAME)
    if (!recoveryRoot.mkdirs() && !recoveryRoot.isDirectory) {
        recoveryFailure("немаєЗаконстворитивстановити Skill відновитискладнітимчасовожитиГоловазапис")
    }
    if (Files.isSymbolicLink(recoveryRoot.toPath()) || !isStrictChild(operation, recoveryRoot)) {
        recoveryFailure("Skill відновитискладнітимчасовожитиГоловазаписнемаєвстановитиПовний")
    }
    val staging = File(recoveryRoot, skillId)
    if (!deleteSkillPathWithoutFollowingLinks(operation, staging)) {
        recoveryFailure("немаєЗаконяснопричина Skill відновитискладнітимчасовожитиГоловазапис")
    }
    copyDirectoryWithoutFollowingLinks(backup, staging)

    val target = File(skillsRoot, skillId)
    if (!deleteSkillPathWithoutFollowingLinks(skillsRoot, target)) {
        recoveryFailure("немаєЗаконяснопричинаще нізакінченостатинестиплатитиз Skill：$skillId")
    }
    directoryMover.move(staging, target)
}

private fun copyDirectoryWithoutFollowingLinks(source: File, target: File) {
    if (Files.isSymbolicLink(source.toPath()) || !source.isDirectory) {
        recoveryFailure("Skill ПриготуйтечасткаГоловазаписнемаєвстановитиПовний")
    }
    if (!target.mkdir()) recoveryFailure("немаєЗаконстворитивстановити Skill відновитискладніпороккнига")
    val children = source.listFiles() ?: recoveryFailure("немаєЗакончитатиВиберіть Skill ПриготуйтечасткаГоловазапис")
    children.forEach { child ->
        if (Files.isSymbolicLink(child.toPath())) {
            recoveryFailure("Skill ПриготуйтечасткаСумкаМіститьсимволНомерланцюгзловити")
        }
        val destination = File(target, child.name)
        when {
            child.isDirectory -> copyDirectoryWithoutFollowingLinks(child, destination)
            child.isFile -> Files.copy(child.toPath(), destination.toPath())
            else -> recoveryFailure("Skill ПриготуйтечасткаСумкаМіститьнемаєвідділенняутримуватизмистецтваштуквидтипу")
        }
    }
}

private fun parseJournal(journalFile: File): List<SkillRecoveryRecord> {
    val json = runCatching { JSONObject(journalFile.readText(Charsets.UTF_8)) }
        .getOrElse { recoveryFailure("Skill відновитискладніденьчестолюбствосіткаРежимнемаєефект") }
    if (json.optInt("version", -1) != JOURNAL_VERSION) {
        recoveryFailure("Skill відновитискладніденьчестолюбствоверсіякниганемаєзавідділенняутримувати")
    }
    val entries = json.optJSONArray("skills") ?: recoveryFailure("Skill відновитискладніденьчестолюбствовідсутністьмало skills")
    if (entries.length() !in 1..MAX_JOURNAL_SKILLS) {
        recoveryFailure("Skill відновитискладніденьчестолюбствосмужкаГолованомернемаєефект")
    }
    val records = (0 until entries.length()).map { index ->
        val entry = entries.optJSONObject(index) ?: recoveryFailure("Skill відновитискладніденьчестолюбствосмужкаГолованемаєефект")
        val id = entry.optString("id")
        if (!entry.has("originalTargetExisted") || entry.opt("originalTargetExisted") !is Boolean) {
            recoveryFailure("Skill відновитискладніденьчестолюбствовідсутністьмалоОригіналГоловазнакформустан")
        }
        if (!entry.has("backupCompleted") || entry.opt("backupCompleted") !is Boolean) {
            recoveryFailure("Skill відновитискладніденьчестолюбствовідсутністьмалоПриготуйтечасткаформустан")
        }
        if (!entry.has("newTargetCommitted") || entry.opt("newTargetCommitted") !is Boolean) {
            recoveryFailure("Skill відновитискладніденьчестолюбствовідсутністьмалонестиплатитиформустан")
        }
        SkillRecoveryRecord(
            id = id,
            originalTargetExisted = entry.getBoolean("originalTargetExisted"),
            backupCompleted = entry.getBoolean("backupCompleted"),
            newTargetCommitted = entry.getBoolean("newTargetCommitted"),
            registrySnapshot = parseRegistrySnapshot(entry, id),
        )
    }
    validateRecords(records)
    return records
}

private fun writeJournalAtomically(
    operationDirectory: File,
    records: List<SkillRecoveryRecord>,
) {
    val json = JSONObject()
        .put("version", JOURNAL_VERSION)
        .put(
            "skills",
            JSONArray().apply {
                records.forEach { record ->
                    put(
                        JSONObject()
                            .put("id", record.id)
                            .put("originalTargetExisted", record.originalTargetExisted)
                            .put("backupCompleted", record.backupCompleted)
                            .put("newTargetCommitted", record.newTargetCommitted)
                            .put(
                                "registry",
                                JSONObject()
                                    .put("entryExisted", record.registrySnapshot.entryExisted)
                                    .put("enabled", record.registrySnapshot.enabled)
                                    .put("source", record.registrySnapshot.source)
                                    .put("installState", record.registrySnapshot.installState)
                            )
                    )
                }
            }
        )
    val journal = File(operationDirectory, JOURNAL_FILE_NAME)
    val temporary = File(operationDirectory, ".$JOURNAL_FILE_NAME-${UUID.randomUUID()}")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                journal.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                journal.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } finally {
        Files.deleteIfExists(temporary.toPath())
    }
}

private fun validateOperationDirectory(skillsRoot: File, operationDirectory: File) {
    val workRoot = skillInstallerWorkRoot(skillsRoot)
    if (
        Files.isSymbolicLink(operationDirectory.toPath()) || !operationDirectory.isDirectory ||
        operationDirectory.parentFile?.canonicalFile != workRoot.canonicalFile ||
        !OPERATION_NAME_REGEX.matches(operationDirectory.name)
    ) {
        recoveryFailure("Skill ТримайтеробитиГоловазаписнемаєвстановитиПовний")
    }
}

private fun validateRecords(records: List<SkillRecoveryRecord>) {
    if (records.map { it.id }.distinct().size != records.size) {
        recoveryFailure("Skill відновитискладніденьчестолюбствоСумкаМіститьВажкаскладні id")
    }
    records.forEach { record ->
        if (record.id.length !in 1..64 || !SKILL_ID_REGEX.matches(record.id)) {
            recoveryFailure("Skill відновитискладніденьчестолюбствоСумкаМіститьнемаєЗакон id")
        }
        if (!record.originalTargetExisted && record.backupCompleted) {
            recoveryFailure("Повнийновий Skill звідновитискладніденьчестолюбствоСумкаМіститьнемаєЗаконПриготуйтечасткаформустан")
        }
        if (record.originalTargetExisted && record.newTargetCommitted && !record.backupCompleted) {
            recoveryFailure("дляЗміна Skill звідновитискладніденьчестолюбствоформустаннемаєодиндо")
        }
        val registry = record.registrySnapshot
        if (registry.skillId != record.id) recoveryFailure("Skill відновитискладніденьчестолюбство registry id немаєодиндо")
        if (registry.entryExisted) {
            if (
                registry.source !in VALID_REGISTRY_SOURCES ||
                registry.installState !in VALID_INSTALL_STATES ||
                (registry.source == USER_SKILL_SOURCE &&
                    registry.installState != INSTALL_STATE_INSTALLED_VALUE)
            ) {
                recoveryFailure("Skill відновитискладніденьчестолюбствоСумкаМіститьнемаєЗакон registry формустан")
            }
        } else if (
            registry.enabled || registry.source.isNotEmpty() || registry.installState.isNotEmpty()
        ) {
            recoveryFailure("немаєжитиіснуютьз registry швидкийВідповідно доСумкаМіститьЛобназовніформустан")
        }
    }
}

private fun parseRegistrySnapshot(
    entry: JSONObject,
    skillId: String,
): SkillRegistryRecoverySnapshot {
    val registry = entry.optJSONObject("registry")
        ?: recoveryFailure("Skill відновитискладніденьчестолюбствовідсутністьмало registry швидкийВідповідно до")
    val booleanKeys = listOf("entryExisted", "enabled")
    if (booleanKeys.any { key -> !registry.has(key) || registry.opt(key) !is Boolean }) {
        recoveryFailure("Skill відновитискладніденьчестолюбство registry швидкийВідповідно донемаєефект")
    }
    if (!registry.has("source") || registry.opt("source") !is String) {
        recoveryFailure("Skill відновитискладніденьчестолюбство registry source немаєефект")
    }
    if (!registry.has("installState") || registry.opt("installState") !is String) {
        recoveryFailure("Skill відновитискладніденьчестолюбство registry installState немаєефект")
    }
    return SkillRegistryRecoverySnapshot(
        skillId = skillId,
        entryExisted = registry.getBoolean("entryExisted"),
        enabled = registry.getBoolean("enabled"),
        source = registry.getString("source"),
        installState = registry.getString("installState"),
    )
}

private fun isSafeExistingTarget(skillsRoot: File, target: File): Boolean =
    !Files.isSymbolicLink(target.toPath()) && target.isDirectory && isStrictChild(skillsRoot, target)

private fun isStrictChild(root: File, target: File): Boolean {
    val rootPath = root.canonicalFile.toPath()
    val targetPath = runCatching { target.canonicalFile.toPath() }.getOrNull() ?: return false
    return targetPath.startsWith(rootPath) && targetPath != rootPath
}

internal fun skillInstallerWorkRoot(skillsRoot: File): File = File(
    requireNotNull(skillsRoot.canonicalFile.parentFile) { "Skills ГоловазаписповиненОбов'язковоматибатькоГоловазапис" },
    ".eta-skill-installer",
)

private fun recoveryFailure(message: String): Nothing =
    throw SkillRecoveryRequiredException(message)

private const val JOURNAL_VERSION = 1
internal const val JOURNAL_FILE_NAME = "pending-install.json"
private const val BACKUP_DIRECTORY_NAME = "backup"
private const val RECOVERY_DIRECTORY_NAME = "recovery"
private const val OPERATION_PREFIX = "operation-"
private const val MAX_JOURNAL_BYTES = 256L * 1024L
private const val MAX_JOURNAL_SKILLS = 2_048
private const val INSTALL_STATE_INSTALLED_VALUE = "installed"
private const val INSTALL_STATE_REMOVED_BUILTIN_VALUE = "removed_builtin"
private val VALID_REGISTRY_SOURCES = setOf(USER_SKILL_SOURCE, BUILTIN_SKILL_SOURCE)
private val VALID_INSTALL_STATES = setOf(
    INSTALL_STATE_INSTALLED_VALUE,
    INSTALL_STATE_REMOVED_BUILTIN_VALUE,
)
private val OPERATION_NAME_REGEX = Regex("^operation-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val SKILL_ID_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
