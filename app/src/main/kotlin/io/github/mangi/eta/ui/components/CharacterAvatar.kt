package io.github.mangi.eta.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/** Інформація про особу персонажа в розмовах і списках; коли шлях до аватара порожній, ім’я використовується для створення аватара субтитрів.*/
@Immutable
internal data class RoleplayChatUi(
    val characterName: String,
    val avatarPath: String?,
    val userName: String,
)

/** Роль, прив'язана до поточного діалогу; звичайний діалог агента є нульовим, а повідомлення та порожній стан перемикаються на стиль ролі відповідно.*/
internal val LocalRoleplayChat = staticCompositionLocalOf<RoleplayChatUi?> { null }

/**
 * Кеш декодування зображень символів. Оригінальне зображення картки символу зазвичай є PNG понад 1024 пікселів, яке зменшується відповідно до розміру дисплея, а потім кешується.
 * Прокручування списків і реорганізація повідомлень зачіпають лише пам'ять; ключ містить цільовий розмір, щоб уникнути повторного використання великих зображень із маленькими аватарами для витрачання пам’яті.
 */
private object CharacterImageCache {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun peek(path: String, maxPx: Int): ImageBitmap? = cache.get(key(path, maxPx))

    suspend fun load(path: String, maxPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(key(path, maxPx))?.let { return@withContext it }
        val file = File(path)
        if (!file.isFile) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        bitmap.asImageBitmap().also { cache.put(key(path, maxPx), it) }
    }

    // Редагування аватара призведе до створення нового імені файлу, вміст того самого шляху залишається незмінним, а час модифікації файлу включається в ключ лише для запобігання зовнішньому перезапису.
    private fun key(path: String, maxPx: Int) = "$path#${File(path).lastModified()}#$maxPx"
}

/** Асинхронне декодування зображення символу відповідно до розміру дисплея; при попаданні в кеш зображення буде доступним у першому кадрі, а словесний знак не блиматиме.*/
@Composable
internal fun rememberCharacterImage(path: String?, displaySize: Dp): ImageBitmap? {
    val maxPx = with(LocalDensity.current) { displaySize.roundToPx() }.coerceIn(32, 1024)
    val image by produceState(initialValue = path?.let { CharacterImageCache.peek(it, maxPx) }, path, maxPx) {
        value = path?.let { CharacterImageCache.load(it, maxPx) }
    }
    return image
}

/**
 * Аватар персонажа: якщо є зображення, обріжте його та заповніть. Обличчя персонажа зазвичай знаходиться вгорі зображення, тому вирівняйте його вертикально до верху;
 * Якщо зображення немає, використовуйте ініціали імені та стабільний колір для створення словесного знака. Один і той самий символ матиме однаковий колір на всіх сторінках.
 */
@Composable
internal fun CharacterAvatar(
    name: String,
    avatarPath: String?,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier.size(size),
    image: ImageBitmap? = rememberCharacterImage(avatarPath, size),
) {
    // розмір визначає роздільну здатність декодування; розмір макета визначається модифікатором. За умовчанням встановлено квадратний розмір, і абонент може змінити його на вертикальний або повний.
    Box(
        modifier = modifier.clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            CharacterMonogram(name = name, size = size, modifier = Modifier.matchParentSize())
        }
    }
}

@Composable
internal fun CharacterMonogram(name: String, size: Dp, modifier: Modifier = Modifier.size(size)) {
    val gradient = monogramGradient(name)
    Box(
        modifier = modifier.background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** Відповідність кольору словесного знака: двоколірний градієнт низької насиченості, білий текст чіткий під темними та світлими темами кольорів; колір хешується за назвою, результат стабільний.*/
private val MonogramPalette = listOf(
    listOf(Color(0xFFF58FA8), Color(0xFFC56CD6)),
    listOf(Color(0xFF7FA7F5), Color(0xFF6A6FE0)),
    listOf(Color(0xFF6CC9B4), Color(0xFF3E9FB8)),
    listOf(Color(0xFFF5B36C), Color(0xFFE07A5F)),
    listOf(Color(0xFFA48BEB), Color(0xFF6C63C9)),
    listOf(Color(0xFF8CC97A), Color(0xFF4FA37C)),
)

internal fun monogramGradient(name: String): List<Color> =
    MonogramPalette[Math.floorMod(name.trim().hashCode(), MonogramPalette.size)]
