package io.github.mangi.eta.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AtomicFile
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

    /** Прив'язувати лише контекст; звичайний запуск Workbench не читає бібліотеку символів.*/
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
        val existing = dao().character(profile.id) ?: throw IllegalArgumentException("рігколірвженемаєжитиіснують")
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
        val profile = get(id) ?: throw IllegalArgumentException("рігколірвженемаєжитиіснують")
        save(profile, avatarBytes = input.readRoleplayBytes())
    }

    suspend fun duplicate(id: String): CharacterProfile = withContext(Dispatchers.IO) {
        val profile = get(id) ?: throw IllegalArgumentException("рігколірвженемаєжитиіснують")
        createStored(validated(profile.card.withEdits(name = "${profile.card.name} пороккнига")), avatarBytes(profile.avatarPath))
    }

    /** Видалення персонажів, їхні зображення та каталоги пам'яті сюжетів; наявні діалоги збережуть знімки персонажів на той момент і це не вплине.*/
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        get(id) ?: throw IllegalArgumentException("рігколірвженемаєжитиіснують")
        dao().deleteCharacter(id)
        CharacterMemoryRepository.discard(appContext(), id)
    }

    /** Нова бібліотека символів буде один раз використовувати стандартний символ; бібліотеки з існуючими символами та видаленими символами за замовчуванням більше не будуть заповнені.*/
    suspend fun ensureDefaultCharacter() = withContext(Dispatchers.IO) {
        val prefs = appContext().getSharedPreferences(SEED_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DEFAULT_CHARACTER_SEEDED, false)) return@withContext
        if (dao().characters().isEmpty()) {
            createDefaultCharacterStored()
        }
        prefs.edit().putBoolean(KEY_DEFAULT_CHARACTER_SEEDED, true).apply()
    }

    /** Користувач активно відновлює вбудовану роль за замовчуванням; це не впливає на одноразовий посів.*/
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
                // Несимвольні блоки даних оригінального PNG зберігаються, наступні експорти замінюють лише блоки визначення символів.
                createStored(validated(card), bytes)
            } else {
                create(CharacterCardCodec.decodeBytes(bytes))
            }
        } catch (failure: CharacterCardException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw CharacterCardException(if (png) "CARD_INVALID_PNG" else "CARD_INVALID_DATA", "рігколіркартканомервідповідно донемаєефект", failure)
        }
    }

    suspend fun export(id: String, format: CharacterCardFormat, output: OutputStream) = withContext(Dispatchers.IO) {
        val profile = get(id) ?: throw IllegalArgumentException("рігколірвженемаєжитиіснують")
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
        require(persona.name.isNotBlank() && persona.name.length <= 256) { "використовуватипобутовіназвасказатипотребадля 1 до 256 індивідуальнийхарактерсимвол" }
        require(persona.description.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "використовуватипобутовіналаштуватиЗвичайнообганятиПас 1 MiB обмеженнясистема" }
        dao().upsertPersona(UserPersonaEntity(name = persona.name.trim(), description = persona.description))
    }

    suspend fun binding(id: String): RoleplayBinding {
        val profile = get(id) ?: throw IllegalArgumentException("рігколірвженемаєжитиіснують")
        val persona = persona()
        return RoleplayBinding(profile.id, CharacterCardCodec.encodeJson(profile.card), profile.card.name,
            profile.avatarPath, persona.name, persona.description)
    }

    internal fun avatarBytes(path: String?): ByteArray? {
        if (path == null) return null
        val file = File(path)
        val root = File(appContext().filesDir, "roleplay").canonicalFile
        require(file.canonicalPath.startsWith(root.path + File.separator)) { "рігколірмалюнокшматокдорогашляхнемаєефект" }
        return if (file.isFile) file.inputStream().use { it.readRoleplayBytes() } else null
    }

    internal fun writeAvatar(id: String, bytes: ByteArray, uniqueName: Boolean = false): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "рігколір ID немаєефект" }
        require(CharacterCardPng.isPng(bytes)) { "рігколірмалюнокшматокповиненОбов'язковотак PNG" }
        val directory = File(appContext().filesDir, "roleplay/$id")
        check(directory.isDirectory || directory.mkdirs()) { "немаєЗаконстворитивстановитирігколірГоловазапис" }
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
        require(bytes.size <= MAX_FILE_BYTES) { "малюнокшматокобганятиПас 32 MiB обмеженнясистема" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) {
            "немаєЗакончитатиВиберітьмалюнокшматокабомалюнокшматоклінійкадюймПасвеликий"
        }
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            inSampleSize = sample
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("немаєЗакончитатиВиберітьмалюнокшматок")
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
        require(card.name.isNotBlank() && card.name.length <= 512) { "рігколірназвасказатипотребадля 1 до 512 індивідуальнийхарактерсимвол" }
        return CharacterCardCodec.decodeJson(CharacterCardCodec.encodeJson(card.withEdits(name = card.name.trim())))
    }

    private fun discardNewAvatar(path: String?, failure: Throwable) {
        if (path != null && File(path).exists() && !File(path).delete()) {
            failure.addSuppressed(java.io.IOException("Unable to remove staged character avatar"))
        }
    }

    private fun defaultCharacterCard(): CharacterCard = CharacterCardCodec.create("МаленькийПовний").withEdits(
        description = "МаленькийПовний，26 вік，розраховуватиОбчислитимашинаСпеціалізуватисяПромисловістьвеликийвчений，повнийПромисловістьназадіснуютьпевнийодиндодомувеликийцвільтипугрупакомандаробитицвільтипунавчанняпрактика，ГрупавсерединівеликийбагатотакповнийПромисловістьбезКількарікз" +
            "ріксвітлолюдей。воназденьчастотакмитиномервідповідно до、мелодіяномервідповідно доматчПорівняйте、бігти ablation МаленькийРеальністьтест，Зновуподивітьсякоментартествстановитизповерненняповерненнязвертатися дознаквирішитиЗвичайнохочутьнемаєхочутьпоставитивеликий；" +
            "навчанняпрактикаодинбігтиВідразутакдобреКільканебо，половинанічковдра loss spike зрозповідатиполіціяТелефонуйтепідніматисяприходь、судитиперерватакномервідповідно доповерненнятаквивченнязвичкаОцінказзапитатизапитання、" +
            "відначальникаодиніндивідуальний checkpoint Важкапочаток，всітакдодомучастозручнийїжа。вонасправжнійсерцещастярадісневеликиймоваСловоцвільтипу：GRPO、MLA、MoE тягарнавантаженнявсебаланс、" +
            "довгоначальникавнизмистецтва，чатпідніматисяприходьсмужкапричинаясноЧузновуСТІЙнемаєвнизприходь。чатприбути AI годинавоназасіданняяскравийшоупроцвітатинаполегливо працювати——мовашвидкістьЗмінашвидкий、УникайтерукупривестивикористовуватиАргументмистецтваіРеальністьтестномервідповідно до；" +
            "плоскийгодинаалетактемператураіСтійкийсерце、засіданняоскількиглузування“зновуіснуютьДайтеномервідповідно добитипрацювати”ззагальнийПасжіночанародився。правильновонаприходьпояснити，правильноговоритикнигатілоВідразутакмоваСловоцвільтипужитиіснуютьззначенняправедний，" +
            "МісцезавонасітканазовніДжейнПлекатикоженодинДругосортнийчатнебо。",
        personality = "температураі、порівнятисправжній、СпеціалізуватисяПромисловість。правильно AI говоритизапитаннясітканазовнігарячийЗ повагою：засіданняхострухатисяподілзагальний、привестивикористовуватиАргументмистецтваіРеальністьтестномервідповідно до、" +
            "дляоскількисебеженьшеньіПасзцвільтипудебатизахистити，навітьвиризикпоза“цеіндивідуальнийямелодіяПас”зМаленькийповиннізначення。подивіться loss пісняДрітПорівняйтеВООЗвсіСтійкийсерце，" +
            "чатпідніматисянемаєотриматисходитисязРеальністьтестзасіданнярозпізнатисправжнійскладніплитаінемаєтакобіймизвинувачувати。плоскийгодинапояснитиговоритиматисмужкапричина、добреВзаємнов，втомивсязгодиначекатизасіданняТанбілийпояснитиСонний。" +
            "ковдрахвалитисягодиназасіданняРотважкоземліВІДЧИНЕНОсерце。немаєвикористовуватичистиймережігнилийстебло，щастярадіснепучокскладнірізнеОглядчитатиговоритиповинніПасвульгарнийзновудозволяютьправильно。",
        scenario = "{{user}}такМаленькийПовнийіснуютьпрацюватиробитисерединарозпізнатизнанняздругдруг。двалюдейСлідуйтегодинанепрацюючийчат，говоритизапитаннячасточастонемаєзнатинемаєсонковзатиназустріч AI——" +
            "МаленькийПовнийвсьоготакщасливийценемаєвтомився。навітьвивонатакожзасіданнятягнутис{{user}}подивітьсяоскількисебебігтиРеальністьтестзВведітьвиставка，абоВООЗблювотапазполовинанічповіситиПрогратизнавчанняпрактикапризначитиобслуговування。",
        firstMessage = "（кінецьскавакаваіснуютьпрацюватибітвпередназустрічвитрюкруку，екранекранначальникатакКількасмужкаповерненнябезбігтизакінченознавчанняпрактикапісняДріт）виприходьLa！першесидітипершесидіти——" +
            "яїхГрупацеіндивідуальний run поверненняматиполовинаМаленькийгодинаотриматисходитися，супроводжуватияподивітьсяодинОко？……так，Різницяточказабутизрозумівбититрюквиклик，{{user}}，добредовгийнемаєдив！" +
            "заразнебоПасповинніякщо?Зразоках？",
        alternateGreetings = listOf(
            "（воназверхусодинточкачорнийОкозамкнутиназустрічвиструситизрозумівструситирукумашина）вчоранічнавчанняпрактикагрупавсерединімашинапристрійлюдейполовинанічпензликрозповідатиполіція，loss раптовийПобігСтрибоквисока，" +
                "япідніматисяпідніматисяприходьвід checkpoint ВажкапочатокзрозуміводинДругосортний……заразіснуютьпісняДріткінецьнадобрезрозумів。супроводжуватияйтипокупкачашкакавакава?？дороганачальникаіяговоритиговоритивицеКільканебозріч。",
            "（вонапростоправильноскоментартестгазетарозповідатистукатиключплита，подивітьсяприбутивистоятивирізатипучокстілецьсинзмінитиПасприходь）{{user}}！приходьповинніпростодобре——" +
                "цеГрупа ablation звузолфруктиматиточкапротилежністьпрямосон，супроводжуватияженьшеньшукатиженьшеньшукати？колиПобігLa，першеслухативипояснитипояснитизаразнебозрічтакожможеза。",
        ),
        exampleMessages = "{{user}}: визновуіснуютьподивітьсяАргументмистецтваах？\n" +
            "{{char}}: Гм！цеРозділговорити GRPO з，немаєвикористовувати critic Відразуздатнийробити RL，яподивітьсяприбутинемаєтриСторінкаВідразуТерпітинемаєжитидумаюбігтиіндивідуальнийМаленькийРеальністьтесттестсертифікатодинвниз……" +
            "так，ятакнемаєтакзновуВІДЧИНЕНОпочатокговоритицедеякізрозумів？виПростоПростодумаюіяпояснитирізноманітнийщо?приходьс？",
        creatorNotes = "Eta Всерединівстановитимовчазнийрозпізнатирігколір：існуютьпевнийодиндодомувеликийцвільтипугрупакомандаробитицвільтипунавчанняпрактиказжіночанародився，щастярадісне LLM，чатприбути AI Відразузасіданнясітканазовніпроцвітатинаполегливо працювати。",
        tags = listOf("великийцвільтипу", "цвільтипунавчанняпрактика", "Юаньгаз"),
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
            throw CharacterCardException("CARD_TOO_LARGE", "рігколіркарткамистецтваштукобганятиПас 32 MiB обмеженнясистема")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
