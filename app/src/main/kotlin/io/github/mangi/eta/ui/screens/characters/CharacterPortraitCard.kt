package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.ui.components.CharacterAvatar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Вертикальне співвідношення картки персонажа відповідає вихідній картці персонажа (2:3), а верхня частина тіла персонажа зберігається під час обрізання.*/
internal const val CharacterPortraitAspect = 2f / 3f
internal val CharacterPortraitCorner = 20.dp

/**
 * Картка бібліотеки символів: повністю закрите зображення, а нижня частина поступово темніє для розміщення назви та мітки, що запобігає тому, що стовпці зображення та тексту виглядають як елементи налаштування.
 * Зірка колекції висить у верхньому правому куті, натисніть її, щоб не вводити деталі.
 */
@Composable
internal fun CharacterPortraitCard(
    profile: CharacterProfile,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = profile.card
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(CharacterPortraitAspect)
            .squircleClip(CharacterPortraitCorner)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        CharacterAvatar(
            name = card.name,
            avatarPath = profile.avatarPath,
            size = maxWidth.coerceAtLeast(maxHeight),
            shape = RectangleShape,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleFavorite),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.28f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (card.favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    contentDescription = if (card.favorite) "Виберітьвидалитиотриматитибетська" else "отриматитибетська",
                    modifier = Modifier.size(16.dp),
                    tint = if (card.favorite) FavoriteColor else Color.White,
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = card.name,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    card.tags.take(2).forEach { tag -> PortraitTag(tag) }
                }
            }
        }
    }
}

@Composable
private fun PortraitTag(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = Color.White.copy(alpha = 0.92f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Теплий золотий колір зірки колекції, фіксоване значення гарантує, що її можна впізнати на будь-якому аватарі.*/
internal val FavoriteColor = Color(0xFFFFC94D)

/**
 * Нова картка в кінці сітки: заповнювач штрихів такого ж розміру, як і картка персонажа, з двома входами у верхній половині та нижній,
 * Візуально слабше реальних персонажів і не викрадає основний вміст.
 */
@Composable
internal fun CharacterAddTile(
    enabled: Boolean,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .aspectRatio(CharacterPortraitAspect)
            .squircleClip(CharacterPortraitCorner)
            .background(colors.surfaceContainer)
            .squircleBorder(0.5.dp, colors.outline.copy(alpha = 0.5f), CharacterPortraitCorner),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onCreate),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(colors.primary.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(24.dp), tint = colors.primary)
            }
            Spacer(Modifier.height(12.dp))
            Text("новийвстановитирігколір", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, color = colors.onSurface)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.outline.copy(alpha = 0.4f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onImport)
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariantSummary)
            Spacer(Modifier.width(6.dp))
            Text("керівництвовведітьрігколіркартка", style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
        }
    }
}
