package io.github.mangi.eta.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AtomicFile
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.agent.roleplay.CharacterCard
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.CharacterCardException
import io.github.mangi.eta.agent.roleplay.CharacterCardFormat
import io.github.mangi.eta.agent.roleplay.CharacterCardPng
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.UserPersona
import io.github.mangi.eta.data.db.CharacterEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.UserPersonaEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object CharacterRepository {
    const val MAX_FILE_BYTES = CharacterCardPng.MAX_FILE_BYTES
    private const val SEED_PREFS = "eta_roleplay"
    private const val KEY_DEFAULT_CHARACTER_SEEDED = "default_character_seeded"
    @Volatile private var context: Context? = null

    /** 仅绑定上下文；普通工作台启动不读取角色库。 */
    fun initialize(context: Context) { this.context = context.applicationContext }

    private fun appContext() = checkNotNull(context) { "CharacterRepository is not initialized" }
    private fun dao() = EtaDatabase.get(appContext()).characterDao()

    suspend fun list(): List<CharacterProfile> = withContext(Dispatchers.IO) {
        dao().characters().map { it.toProfile() }
    }

    suspend fun get(id: String): CharacterProfile? = withContext(Dispatchers.IO) { dao().character(id)?.toProfile() }

    suspend fun create(card: CharacterCard, avatarBytes: ByteArray? = null): CharacterProfile = withContext(Dispatchers.IO) {
        val validated = validated(card)
        createStored(validated, avatarBytes?.let(::normalizeAvatar))
    }

    private suspend fun createStored(card: CharacterCard, avatarBytes: ByteArray?): CharacterProfile {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val avatar = avatarBytes?.let { writeAvatar(id, it) }
        val profile = CharacterProfile(id, card, avatar, createdAt = now, updatedAt = now)
        try {
            dao().upsertCharacter(profile.toEntity())
        } catch (failure: Throwable) {
            discardNewAvatar(avatar, failure)
            throw failure
        }
        return profile
    }

    suspend fun save(profile: CharacterProfile, avatarBytes: ByteArray? = null): CharacterProfile = withContext(Dispatchers.IO) {
        val existing = dao().character(profile.id) ?: throw IllegalArgumentException(AppStrings.get(R.string.char_repo_not_found, "Character no longer exists"))
        val card = validated(profile.card)
        val avatar = avatarBytes?.let { writeAvatar(profile.id, normalizeAvatar(it), uniqueName = true) }
        val saved = profile.copy(card = card, avatarPath = avatar ?: profile.avatarPath,
            createdAt = existing.createdAt, updatedAt = System.currentTimeMillis())
        try {
            dao().upsertCharacter(saved.toEntity())
        } catch (failure: Throwable) {
            discardNewAvatar(avatar, failure)
            throw failure
        }
        saved
    }

    suspend fun replaceAvatar(id: String, input: InputStream): CharacterProfile = withContext(Dispatchers.IO) {
        val profile = get(id) ?: throw IllegalArgumentException(AppStrings.get(R.string.char_repo_not_found, "Character no longer exists"))
        save(profile, avatarBytes = input.readRoleplayBytes())
    }

    suspend fun duplicate(id: String): CharacterProfile = withContext(Dispatchers.IO) {
        val profile = get(id) ?: throw IllegalArgumentException(AppStrings.get(R.string.char_repo_not_found, "Character no longer exists"))
        createStored(validated(profile.card.withEdits(name = AppStrings.get(R.string.char_repo_copy_suffix, "%1\$s Copy", profile.card.name))), avatarBytes(profile.avatarPath))
    }

    /** 删除角色及其图片、剧情记忆目录；已有对话保留当时的角色快照，不受影响。 */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        get(id) ?: throw IllegalArgumentException(AppStrings.get(R.string.char_repo_not_found, "Character no longer exists"))
        dao().deleteCharacter(id)
        CharacterMemoryRepository.discard(appContext(), id)
    }

    /** 新角色库播种一次默认角色；已有角色的库和删除过默认角色都不再播种。 */
    suspend fun ensureDefaultCharacter() = withContext(Dispatchers.IO) {
        val prefs = appContext().getSharedPreferences(SEED_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DEFAULT_CHARACTER_SEEDED, false)) return@withContext
        if (dao().characters().isEmpty()) {
            createDefaultCharacterStored()
        }
        prefs.edit().putBoolean(KEY_DEFAULT_CHARACTER_SEEDED, true).apply()
    }

    /** 用户主动恢复内置默认角色；与一次性播种互不影响。 */
    suspend fun createDefaultCharacter(): CharacterProfile = withContext(Dispatchers.IO) {
        createDefaultCharacterStored()
    }

    private suspend fun createDefaultCharacterStored(): CharacterProfile =
        createStored(validated(defaultCharacterCard()), avatarBytes = null)

    suspend fun import(input: InputStream): CharacterProfile = withContext(Dispatchers.IO) {
        val bytes = input.readRoleplayBytes()
        val png = CharacterCardPng.isPng(bytes)
        try {
            if (png) {
                val card = CharacterCardPng.read(bytes)
                // 保留原始 PNG 的非角色数据块，后续导出只替换角色定义块。
                createStored(validated(card), bytes)
            } else {
                create(CharacterCardCodec.decodeBytes(bytes))
            }
        } catch (failure: CharacterCardException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw CharacterCardException(if (png) "CARD_INVALID_PNG" else "CARD_INVALID_DATA", AppStrings.get(R.string.char_repo_invalid_data, "Invalid character card data"), failure)
        }
    }

    suspend fun export(id: String, format: CharacterCardFormat, output: OutputStream) = withContext(Dispatchers.IO) {
        val profile = get(id) ?: throw IllegalArgumentException(AppStrings.get(R.string.char_repo_not_found, "Character no longer exists"))
        val bytes = when (format) {
            CharacterCardFormat.JSON -> CharacterCardCodec.encodeJson(profile.card).toByteArray(Charsets.UTF_8)
            CharacterCardFormat.PNG -> CharacterCardPng.write(avatarBytes(profile.avatarPath) ?: defaultAvatar(profile.card.name), profile.card)
        }
        output.write(bytes)
        output.flush()
    }

    suspend fun persona(): UserPersona = withContext(Dispatchers.IO) {
        dao().persona()?.let { UserPersona(it.name, it.description) } ?: UserPersona()
    }

    suspend fun savePersona(persona: UserPersona) = withContext(Dispatchers.IO) {
        require(persona.name.isNotBlank() && persona.name.length <= 256) {
            AppStrings.get(R.string.char_repo_persona_name_len, "User name must be 1 to 256 characters")
        }
        require(persona.description.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) {
            AppStrings.get(R.string.char_repo_persona_desc_len, "User description exceeds 1 MiB limit")
        }
        dao().upsertPersona(UserPersonaEntity(name = persona.name.trim(), description = persona.description))
    }

    suspend fun binding(id: String): RoleplayBinding {
        val profile = get(id) ?: throw IllegalArgumentException(AppStrings.get(R.string.char_repo_not_found, "Character no longer exists"))
        val persona = persona()
        return RoleplayBinding(profile.id, CharacterCardCodec.encodeJson(profile.card), profile.card.name,
            profile.avatarPath, persona.name, persona.description)
    }

    internal fun avatarBytes(path: String?): ByteArray? {
        if (path == null) return null
        val file = File(path)
        val root = File(appContext().filesDir, "roleplay").canonicalFile
        require(file.canonicalPath.startsWith(root.path + File.separator)) {
            AppStrings.get(R.string.char_repo_invalid_avatar_path, "Invalid character image path")
        }
        return if (file.isFile) file.inputStream().use { it.readRoleplayBytes() } else null
    }

    internal fun writeAvatar(id: String, bytes: ByteArray, uniqueName: Boolean = false): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { AppStrings.get(R.string.char_repo_invalid_id, "Invalid character ID") }
        require(CharacterCardPng.isPng(bytes)) { AppStrings.get(R.string.char_repo_avatar_must_be_png, "Character image must be PNG") }
        val directory = File(appContext().filesDir, "roleplay/$id")
        check(directory.isDirectory || directory.mkdirs()) { AppStrings.get(R.string.char_repo_mkdir_failed, "Could not create character directory") }
        val file = File(directory, if (uniqueName) "avatar-${UUID.randomUUID()}.png" else "avatar.png")
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
        return file.absolutePath
    }

    private fun normalizeAvatar(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_FILE_BYTES) { AppStrings.get(R.string.char_repo_image_too_large, "Image exceeds 32 MiB limit") }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) {
            AppStrings.get(R.string.char_repo_image_unreadable_or_large, "Could not read image or dimensions too large")
        }
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            inSampleSize = sample
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException(AppStrings.get(R.string.char_repo_image_unreadable, "Could not read image"))
        return try {
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun defaultAvatar(name: String): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(66, 74, 80))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 192f; textAlign = Paint.Align.CENTER }
        canvas.drawText(name.take(1), 256f, 256f - (paint.ascent() + paint.descent()) / 2, paint)
        return try {
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun validated(card: CharacterCard): CharacterCard {
        require(card.name.isNotBlank() && card.name.length <= 512) { AppStrings.get(R.string.char_repo_name_len, "Character name must be 1 to 512 characters") }
        return CharacterCardCodec.decodeJson(CharacterCardCodec.encodeJson(card.withEdits(name = card.name.trim())))
    }

    private fun discardNewAvatar(path: String?, failure: Throwable) {
        if (path != null && File(path).exists() && !File(path).delete()) {
            failure.addSuppressed(java.io.IOException("Unable to remove staged character avatar"))
        }
    }

    private fun defaultCharacterCard(): CharacterCard = CharacterCardCodec.create(
        AppStrings.get(R.string.char_default_name, "Xiaoman"),
    ).withEdits(
        description = AppStrings.get(R.string.char_default_description, "Xiaoman, 26, holds a Master's in CS and works on LLM training."),
        personality = AppStrings.get(R.string.char_default_personality, "Warm, thorough, and professional."),
        scenario = AppStrings.get(R.string.char_default_scenario, "{{user}} is a friend Xiaoman met through work."),
        firstMessage = AppStrings.get(R.string.char_default_first_msg, "You're here! Have a seat—how's your day going?"),
        alternateGreetings = listOf(
            AppStrings.get(R.string.char_default_alt_msg1, "Grab a coffee with me? Tell me what you've been up to."),
            AppStrings.get(R.string.char_default_alt_msg2, "{{user}}! Perfect timing—tell me about your day!"),
        ),
        exampleMessages = AppStrings.get(R.string.char_default_examples, "{{user}}: Reading papers again?\n{{char}}: Yep!"),
        creatorNotes = AppStrings.get(R.string.char_default_creator_notes, "Eta built-in default character: an LLM training engineer who loves talking about AI."),
        tags = listOf(
            AppStrings.get(R.string.char_default_tag1, "LLM"),
            AppStrings.get(R.string.char_default_tag2, "Training"),
            AppStrings.get(R.string.char_default_tag3, "Upbeat"),
        ),
        creator = "Eta",
        version = "1.0",
    )

    private fun CharacterEntity.toProfile() = CharacterProfile(id, CharacterCardCodec.decodeJson(cardJson), avatarPath, createdAt, updatedAt)
    private fun CharacterProfile.toEntity() = CharacterEntity(id, card.name, CharacterCardCodec.encodeJson(card), avatarPath, false, createdAt, updatedAt)
}

private fun InputStream.readRoleplayBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size().toLong() + count > CharacterRepository.MAX_FILE_BYTES) {
            throw CharacterCardException("CARD_TOO_LARGE", AppStrings.get(R.string.char_repo_card_too_large, "Character card file exceeds 32 MiB limit"))
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
